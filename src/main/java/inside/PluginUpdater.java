package inside;

import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.*;
import arc.math.Mathf;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.Jval;

import java.util.Optional;

import static arc.struct.StringMap.of;

public class PluginUpdater {
    private static final String api = "https://api.github.com", searchTerm = "mindustry plugin";
    private static final int perPage = 100;
    private static final int maxLength = 125; // + '...'
    private static final ObjectSet<String> jvmLangs = ObjectSet.with("Java", "Kotlin", "Groovy"); // obviously not a comprehensive list
    private static final ObjectSet<String> blacklist = ObjectSet.with("Anuken/ExamplePlugin", "MindustryInside/MindustryPlugins");
    private static final String githubToken = OS.prop("githubtoken");

    public static void main(String[] args) {
        Colors.put("accent", Color.white);
        Colors.put("unlaunched", Color.white);
        Colors.put("highlight", Color.white);
        Colors.put("stat",  Color.white);

        Log.info("&lkGithub token is @.", githubToken != null ? "present" : "absent");

        query("/search/repositories", of("q", searchTerm, "per_page", perPage), result -> {
            int total = result.getInt("total_count", 0);
            int pages = Mathf.ceil((float) total / perPage);

            for (int i = 1; i < pages; i++) {
                query("/search/repositories", of("q", searchTerm, "per_page", perPage, "page", i + 1), secresult -> {
                    result.get("items").asArray().addAll(secresult.get("items").asArray());
                });
            }

            query("/search/repositories", of("q", "topic:mindustry-plugin", "per_page", perPage), topicresult -> {
                Seq<Jval> dest = result.get("items").asArray();
                Seq<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                dest.addAll(added);
            });

            result.get("items").asArray().removeAll(v -> v.getBool("is_template", false));

            ObjectMap<String, Jval> output = new ObjectMap<>();
            ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
            Seq<String> names = result.get("items").asArray().map(val -> {
                ghmeta.put(val.get("full_name").toString(), val);
                return val.get("full_name").toString();
            });

            for (String name : blacklist) {
                names.remove(name);
            }

            Log.info("&lcTotal plugins found: @\n", names.size);

            int index = 0;
            for (String name : names) {
                Log.info("&lc[@%] [@]&y: querying...", (int) ((float) index++ / names.size * 100), name);

                try {
                    Jval meta = ghmeta.get(name);
                    String branch = meta.getString("default_branch");
                    Jval pluginjson = tryList(name + "/" + branch + "/plugin.json", name + "/" + branch + "/plugin.hjson",
                            name + "/" + branch + "/src/main/resources/plugin.json", name + "/" + branch + "/src/main/resources/plugin.hjson",
                            name + "/" + branch + "/assets/plugin.json", name + "/" + branch + "/assets/plugin.hjson");

                    if (pluginjson == null) {
                        Log.info("&lc| &lyNo meta found, fallback to file search.");
                        pluginjson = trySearchFile(name, branch, "plugin.json", "plugin.hjson");
                    }

                    if (pluginjson == null) {
                        Log.info("&lc| &lySkipping, no meta found.");
                        continue;
                    }

                    Log.info("&lc|&lg Found plugin meta file!");
                    output.put(name, pluginjson);
                } catch(Throwable t) {
                    Log.info("&lc| &lySkipping. [@]", name, Strings.getSimpleMessage(t));
                }
            }

            Log.info("&lcFound @ valid plugins.", output.size);
            Seq<String> outnames = output.keys().toSeq();
            outnames.sort(Structs.comps(Structs.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)),
                    Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

            Log.info("&lcCreating plugins.json file...");
            Jval array = Jval.newArray();
            for (String name : outnames) {
                Jval gm = ghmeta.get(name);
                Jval pluginj = output.get(name);
                Jval obj = Jval.newObject();
                String displayName = Strings.stripColors(pluginj.getString("displayName", ""))
                        .replace("\\n", "");

                if (displayName.isEmpty()) {
                    displayName = gm.getString("name");
                }

                //skip outdated mods
                String version = pluginj.getString("minGameVersion", "104");
                int minBuild = Strings.parseInt(version.contains(".") ? version.split("\\.")[0] : version, 0);
                if (minBuild < 105) {
                    continue;
                }

                String lang = gm.getString("language", "");

                String metaName = Strings.stripColors(displayName).replace("\n", "");
                if (metaName.length() > maxLength) {
                    metaName = name.substring(0, maxLength) + "...";
                }

                obj.add("repo", name);
                obj.add("name", metaName);
                obj.add("author", Strings.stripColors(pluginj.getString("author", gm.get("owner").get("login").toString())));
                obj.add("lastUpdated", gm.get("pushed_at"));
                obj.add("stars", gm.get("stargazers_count"));
                obj.add("minGameVersion", version);
                obj.add("hasJava", Jval.valueOf(pluginj.getBool("java", false) || jvmLangs.contains(lang)));
                obj.add("description", Strings.stripColors(pluginj.getString("description", "No description provided.")));
                array.asArray().add(obj);
            }

            Fi.get("plugins.json").writeString(array.toString(Jval.Jformat.formatted));

            Log.info("&lcDone. Exiting.");
        });
    }

    @Nullable
    private static Jval tryList(String... queries) {
        Jval[] result = {null};
        for (String str : queries) {
            Http.get("https://raw.githubusercontent.com/" + str)
                    .error(PluginUpdater::simpleError)
                    .block(res -> {
                        if (res.getStatus() == Http.HttpStatus.OK) {
                            result[0] = Jval.read(res.getResultAsString());
                        }
                    });
        }
        return result[0];
    }

    @Nullable
    private static Jval trySearchFile(String repo, String branch, String... files) {
        Jval[] result = {null};
        String[] path = {null};
        for (String str : files) {
            if (path[0] != null) break;
            query("/search/code?q=name+repo:" + Strings.encode(repo) + "+filename:" + Strings.encode(str), null, jval -> {
                path[0] = Optional.ofNullable(jval.get("items"))
                        .map(Jval::asArray)
                        .map(Seq::firstOpt)
                        .map(j -> j.getString("path"))
                        .orElse(path[0]);
            });
        }

        if (path[0] != null) {
            Http.get("https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path[0])
                    .error(PluginUpdater::simpleError)
                    .block(res -> {
                        if (res.getStatus() == Http.HttpStatus.OK) {
                            result[0] = Jval.read(res.getResultAsString());
                        }
                    });
        }
        return result[0];
    }

    private static void simpleError(Throwable t){
        if (!t.getMessage().contains("404")) {
            Log.info("&lc| &lr" + Strings.getSimpleMessage(t));
        }
    }

    private static void query(String url, @Nullable StringMap params, Cons<Jval> cons) {
        Http.get(api + url + (params == null ? "" : "?" + params.keys().toSeq()
                .map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry)))
                .toString("&")))
                .timeout(10000)
                .method(Http.HttpMethod.GET)
                .header("authorization", githubToken)
                .header("accept", "application/vnd.github.baptiste-preview+json")
                .block(res -> {
                    Log.info("&lcSending search query. Status: @; Queries remaining: @/@", res.getStatus(),
                            res.getHeader("X-RateLimit-Remaining"), res.getHeader("X-RateLimit-Limit"));

                    cons.get(Jval.read(res.getResultAsString()));
                });
    }
}

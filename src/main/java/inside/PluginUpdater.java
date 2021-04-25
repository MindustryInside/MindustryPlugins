package inside;

import arc.*;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.*;
import arc.math.Mathf;
import arc.struct.*;
import arc.util.*;
import arc.util.async.*;
import arc.util.serialization.Jval;

import java.util.*;

import static arc.struct.StringMap.of;

public class PluginUpdater {
    static final String api = "https://api.github.com", searchTerm = "mindustry plugin";
    static final int perPage = 100;
    static final int maxLength = 55;
    static final ObjectSet<String> javaLangs = ObjectSet.with("Java", "Kotlin", "Groovy"); // obviously not a comprehensive list
    static final ObjectSet<String> blacklist = ObjectSet.with("Anuken/ExamplePlugin", "MindustryInside/MindustryPlugins");

    public static void main(String[] args) {
        Core.net = makeNet();
        new PluginUpdater();
    }

    {
        Colors.put("accent", Color.white);
        Colors.put("unlaunched",  Color.white);
        Colors.put("highlight",  Color.white);
        Colors.put("stat",  Color.white);

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
            outnames.sort(Structs.comps(Comparator.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)),
                    Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

            Log.info("&lcCreating plugins.json file...");
            Jval array = Jval.read("[]");
            for (String name : outnames) {
                Jval gm = ghmeta.get(name);
                Jval pluginj = output.get(name);
                Jval obj = Jval.read("{}");
                String displayName = Strings.stripColors(pluginj.getString("displayName", ""))
                        .replace("\\n", "");

                if (displayName.isEmpty()) {
                    displayName = gm.getString("name");
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
                obj.add("hasJava", Jval.valueOf(pluginj.getBool("java", false) || javaLangs.contains(lang)));
                obj.add("description", Strings.stripColors(pluginj.getString("description", "No description provided.")));
                array.asArray().add(obj);
            }

            Fi.get("plugins.json").writeString(array.toString(Jval.Jformat.formatted));

            Log.info("&lcDone. Exiting.");
        });
    }

    @Nullable
    Jval tryList(String... queries) {
        Jval[] result = {null};
        for (String str : queries) {
            Core.net.httpGet("https://raw.githubusercontent.com/" + str, out -> {
                if (out.getStatus() == Net.HttpStatus.OK) {
                    result[0] = Jval.read(out.getResultAsString());
                }
            }, t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t)));
        }
        return result[0];
    }

    @Nullable
    Jval trySearchFile(String repo, String branch, String... files) {
        Jval[] result = {null};
        String[] path = {null};
        for (String str : files) {
            if(path[0] != null) break;
            query("/search/code?q=name+repo:" + Strings.encode(repo) + "+filename:" + Strings.encode(str), null, jval -> {
                path[0] = Optional.ofNullable(jval.get("items"))
                        .map(Jval::asArray)
                        .map(Seq::firstOpt)
                        .map(j -> j.getString("path"))
                        .orElse(path[0]);
            });
        }

        if (path[0] != null) {
            Core.net.httpGet("https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path[0], out -> {
                if(out.getStatus() == Net.HttpStatus.OK){
                    result[0] = Jval.read(out.getResultAsString());
                }
            }, t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t)));
        }
        return result[0];
    }

    void query(String url, @Nullable StringMap params, Cons<Jval> cons) {
        Net.HttpRequest req = new Net.HttpRequest()
                .timeout(10000)
                .method(Net.HttpMethod.GET)
                .header("accept", "application/vnd.github.baptiste-preview+json")
                .url(api + url + (params == null ? "" : "?" + params.keys().toSeq()
                        .map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry)))
                        .toString("&")));

        Core.net.http(req, response -> {
            Log.info("&lcSending search query. Status: @; Queries remaining: @/@", response.getStatus(),
                    response.getHeader("X-RateLimit-Remaining"), response.getHeader("X-RateLimit-Limit"));
            try {
                cons.get(Jval.read(response.getResultAsString()));
            } catch(Throwable error) {
                handleError(error);
            }
        }, this::handleError);
    }

    void handleError(Throwable error) {
        error.printStackTrace();
    }

    static Net makeNet() {
        Net net = new Net();
        //use blocking requests
        Reflect.set(NetJavaImpl.class, Reflect.get(net, "impl"), "asyncExecutor", new AsyncExecutor(1) {
            public <T> AsyncResult<T> submit(final AsyncTask<T> task){
                try {
                    task.call();
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        });

        return net;
    }
}

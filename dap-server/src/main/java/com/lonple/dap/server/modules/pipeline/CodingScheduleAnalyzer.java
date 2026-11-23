package com.lonple.dap.server.modules.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编码任务图调度：按 deps 拓扑排序；P0 每卡单独一波（串行）。
 * touch 冲突信息写入 notes，供后续并行扩展。
 */
public final class CodingScheduleAnalyzer {

    private CodingScheduleAnalyzer() {}

    public static ObjectNode analyze(JsonNode planRoot, ObjectMapper mapper) {
        ArrayNode tasks = planRoot != null && planRoot.path("tasks").isArray()
                ? (ArrayNode) planRoot.path("tasks")
                : mapper.createArrayNode();

        Map<String, JsonNode> byId = new HashMap<>();
        List<String> orderIn = new ArrayList<>();
        for (JsonNode t : tasks) {
            String id = text(t, "id");
            if (id.isEmpty()) {
                continue;
            }
            byId.put(id, t);
            orderIn.add(id);
        }

        List<String> sorted = topoSort(orderIn, byId);
        List<String> parallelHints = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                String a = sorted.get(i);
                String b = sorted.get(j);
                JsonNode ta = byId.get(a);
                JsonNode tb = byId.get(b);
                List<String> depsB = stringList(tb.path("deps"));
                if (depsB.contains(a)) {
                    continue;
                }
                Set<String> touchA = normalizeTouch(stringList(ta.path("touch")));
                Set<String> touchB = normalizeTouch(stringList(tb.path("touch")));
                if (!overlaps(touchA, touchB) && depsB.isEmpty()
                        && stringList(ta.path("deps")).isEmpty()) {
                    parallelHints.add(a + "+" + b);
                }
            }
        }

        ObjectNode schedule = mapper.createObjectNode();
        schedule.put("mode", "SERIAL");
        ArrayNode wavesNode = schedule.putArray("waves");
        ArrayNode orderNode = schedule.putArray("order");
        for (String id : sorted) {
            wavesNode.addArray().add(id);
            orderNode.add(id);
        }
        String notes = "P0 默认串行执行各任务卡";
        if (!parallelHints.isEmpty()) {
            notes += "；无依赖且 touch 无交、后续可并行: " + String.join(", ", parallelHints);
        }
        schedule.put("notes", notes);
        return schedule;
    }

    private static List<String> topoSort(List<String> ids, Map<String, JsonNode> byId) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (String id : ids) {
            indeg.put(id, 0);
            adj.put(id, new ArrayList<>());
        }
        for (String id : ids) {
            for (String d : stringList(byId.get(id).path("deps"))) {
                if (!byId.containsKey(d)) {
                    continue;
                }
                adj.get(d).add(id);
                indeg.put(id, indeg.get(id) + 1);
            }
        }
        List<String> q = new ArrayList<>();
        for (String id : ids) {
            if (indeg.get(id) == 0) {
                q.add(id);
            }
        }
        List<String> out = new ArrayList<>();
        while (!q.isEmpty()) {
            String n = q.remove(0);
            out.add(n);
            for (String v : adj.getOrDefault(n, List.of())) {
                indeg.put(v, indeg.get(v) - 1);
                if (indeg.get(v) == 0) {
                    q.add(v);
                }
            }
        }
        if (out.size() < ids.size()) {
            for (String id : ids) {
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static Set<String> normalizeTouch(List<String> raw) {
        Set<String> s = new HashSet<>();
        for (String t : raw) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String n = t.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (n.endsWith("/**")) {
                n = n.substring(0, n.length() - 3);
            }
            if (n.endsWith("/*")) {
                n = n.substring(0, n.length() - 2);
            }
            s.add(n);
        }
        return s;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y) || x.startsWith(y + "/") || y.startsWith(x + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode n : node) {
            if (n != null && n.isValueNode() && !n.asText("").isBlank()) {
                list.add(n.asText().trim());
            }
        }
        return list;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return "";
        }
        String v = n.path(field).asText("");
        return v == null ? "" : v.trim();
    }
}

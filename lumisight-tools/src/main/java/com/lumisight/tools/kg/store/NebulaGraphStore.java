package com.lumisight.tools.kg.store;

import com.vesoft.nebula.client.graph.SessionPool;
import com.vesoft.nebula.client.graph.SessionPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class NebulaGraphStore implements AutoCloseable {

    private static final String SPACE = "lumisight_kg";
    private static final String TAG_KG_NODE = "kg_node";
    private static final String EDGE_KG_REL = "kg_rel";
    private static final String TAG_REPO_META = "repo_meta";

    private final SessionPool sessionPool;

    public NebulaGraphStore(String host, int port, String user, String password) {
        log.info("Initializing Nebula session pool, host={}, port={}, user={}, space={}", host, port, user, SPACE);
        SessionPoolConfig config = new SessionPoolConfig(
                List.of(new HostAddress(host, port)),
                SPACE,
                user,
                password
        );
        this.sessionPool = new SessionPool(config);
        if (!sessionPool.init()) {
            log.error("Nebula session pool init failed, host={}, port={}, user={}, space={}", host, port, user, SPACE);
            throw new IllegalStateException("Failed to init Nebula session pool");
        }
        log.info("Nebula session pool initialized successfully, host={}, port={}, space={}", host, port, SPACE);
        initSchema();
    }

    public String currentRepoCommit(String repoName) {
        String metaVid = metaVid(repoName);
        ResultSet result = execute("FETCH PROP ON " + TAG_REPO_META + " \"" + metaVid + "\" YIELD " + TAG_REPO_META + ".git_commit");
        if (result.rowsSize() == 0) {
            return null;
        }
        try {
            String value = result.rowValues(0).get(0).asString();
            return value == null || value.isBlank() ? null : value;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Failed to decode git_commit from Nebula result", e);
        }
    }

    public void writeRepoMeta(
            String repoName,
            String repoRoot,
            String gitBranch,
            String gitCommit,
            int trackedFileCount,
            int nodeCount,
            int edgeCount
    ) {
        String metaVid = metaVid(repoName);
        execute("INSERT VERTEX " + TAG_REPO_META + "(repo_name, repo_root, git_branch, git_commit, tracked_file_count, node_count, edge_count, updated_at) VALUES " +
                "\"" + metaVid + "\":(" +
                "\"" + escape(repoName) + "\"," +
                "\"" + escape(repoRoot) + "\"," +
                "\"" + escape(gitBranch) + "\"," +
                "\"" + escape(gitCommit) + "\"," +
                trackedFileCount + "," +
                nodeCount + "," +
                edgeCount + "," +
                "datetime())");
    }

    public void writeNode(String vid, Map<String, Object> props) {
        String nGql = "INSERT VERTEX " + TAG_KG_NODE + "(node_id, node_type, name, qualified_name, source_file, repo_name, module_name, package_name, class_name, method_name, parameter_count, start_line, end_line, status, git_branch, git_commit) VALUES " +
                "\"" + escape(vid) + "\":(" +
                quoted(props.get("node_id")) + "," +
                quoted(props.get("node_type")) + "," +
                quoted(props.get("name")) + "," +
                quoted(props.get("qualified_name")) + "," +
                quoted(props.get("source_file")) + "," +
                quoted(props.get("repo_name")) + "," +
                quoted(props.get("module_name")) + "," +
                quoted(props.get("package_name")) + "," +
                quoted(props.get("class_name")) + "," +
                quoted(props.get("method_name")) + "," +
                integerLiteral(props.get("parameter_count")) + "," +
                integerLiteral(props.get("start_line")) + "," +
                integerLiteral(props.get("end_line")) + "," +
                integerLiteral(props.get("status")) + "," +
                quoted(props.get("git_branch")) + "," +
                quoted(props.get("git_commit")) +
                ")";
        execute(nGql);
    }

    public void writeEdge(String fromVid, String toVid, Map<String, Object> props) {
        String nGql = "INSERT EDGE " + EDGE_KG_REL + "(edge_id, edge_type, source_file, status, git_branch, git_commit) VALUES " +
                "\"" + escape(fromVid) + "\"->\"" + escape(toVid) + "\":(" +
                quoted(props.get("edge_id")) + "," +
                quoted(props.get("edge_type")) + "," +
                quoted(props.get("source_file")) + "," +
                integerLiteral(props.get("status")) + "," +
                quoted(props.get("git_branch")) + "," +
                quoted(props.get("git_commit")) +
                ")";
        execute(nGql);
    }

    public void deleteVertices(Set<String> vids) {
        if (vids == null || vids.isEmpty()) {
            return;
        }
        String vidList = vids.stream()
                .map(v -> "\"" + escape(v) + "\"")
                .collect(Collectors.joining(", "));
        execute("DELETE VERTEX " + vidList + " WITH EDGE");
    }

    public void updateNodeStatus(String vid, int status) {
        execute("UPDATE VERTEX ON " + TAG_KG_NODE + " \"" + escape(vid) + "\" SET status = " + status);
    }

    public void updateEdgeStatus(String fromVid, String toVid, int status) {
        execute("UPDATE EDGE ON " + EDGE_KG_REL + " \"" + escape(fromVid) + "\"->\"" + escape(toVid) + "\" SET status = " + status);
    }

    public void markAdjacentEdgesDeleted(String vid) {
        ResultSet result = execute(
                "GO FROM \"" + escape(vid) + "\" OVER " + EDGE_KG_REL + " BIDIRECT " +
                        "YIELD src(edge) AS src, dst(edge) AS dst"
        );
        if (result.rowsSize() == 0) {
            return;
        }
        for (int i = 0; i < result.rowsSize(); i++) {
            try {
                String src = result.rowValues(i).get(0).asString();
                String dst = result.rowValues(i).get(1).asString();
                updateEdgeStatus(src, dst, 1);
            } catch (Exception ignored) {
                // 跳过异常行，避免单条脏数据中断整体构建流程。
            }
        }
    }

    private void initSchema() {
        execute("CREATE SPACE IF NOT EXISTS " + SPACE + "(partition_num=10, replica_factor=1, vid_type=FIXED_STRING(256))");
        execute("USE " + SPACE);
        execute("CREATE TAG IF NOT EXISTS " + TAG_KG_NODE + "(" +
                "node_id string, node_type string, name string, qualified_name string, source_file string, repo_name string, " +
                "module_name string, package_name string, class_name string, method_name string, parameter_count int, start_line int, end_line int, status int, " +
                "git_branch string, git_commit string)");
        execute("CREATE EDGE IF NOT EXISTS " + EDGE_KG_REL + "(" +
                "edge_id string, edge_type string, source_file string, status int, git_branch string, git_commit string)");
        execute("CREATE TAG IF NOT EXISTS " + TAG_REPO_META + "(" +
                "repo_name string, repo_root string, git_branch string, git_commit string, " +
                "tracked_file_count int, node_count int, edge_count int, updated_at datetime)");
    }

    private ResultSet execute(String nGql) {
        try {
            ResultSet result = sessionPool.execute(nGql);
            if (!result.isSucceeded()) {
                log.error("Nebula execute failed, query={}, error={}", nGql, result.getErrorMessage());
                throw new IllegalStateException("Nebula execute failed: " + result.getErrorMessage() + ", query=" + nGql);
            }
            return result;
        } catch (Exception e) {
            log.error("Nebula execute exception, query={}", nGql, e);
            throw new IllegalStateException("Nebula execute failed, query=" + nGql, e);
        }
    }

    private static String quoted(Object value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String integerLiteral(Object value) {
        if (value == null) {
            return "0";
        }
        if (value instanceof Number n) {
            return String.valueOf(n.intValue());
        }
        return String.valueOf(Integer.parseInt(String.valueOf(value)));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String metaVid(String repoName) {
        return "repo:" + repoName;
    }

    @Override
    public void close() {
        log.info("Closing Nebula session pool");
        sessionPool.close();
    }
}

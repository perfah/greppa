package com.example.antiviber;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LSP server listener that receives custom notifications from the Antiviber LSP server.
 */
public class AntiviberLspServerListener implements LspServerListener {

    private static final Logger LOG = Logger.getInstance(AntiviberLspServerListener.class);

    /**
     * All currently running Antiviber LSP servers, so that other components
     * (e.g. the tab selection listener) can send notifications to the
     * server.
     *
     * The platform's LspServerListener interface in this IDE version does
     * not declare serverInitialized/serverStopped callbacks, so the registry
     * cannot be populated from here. Instead, the platform hands the
     * LspServer to AntiviberLspServerDescriptor.createLspServerListener
     * when the server starts; that descriptor calls registerServer with it.
     *
     * Stopped servers are pruned at the send sites: when sending a
     * notification to a server throws, the sender calls unregisterServer,
     * so a dead entry produces at most one error and is then dropped.
     * State-based pruning is not possible in this build: the bundled
     * platform API resolves the LspServerState class but does not expose
     * its constants (the compiler rejects LspServerState.RUNNING and
     * LspServerState.STARTING with "cannot find symbol"), so a failed send
     * is the only reliable liveness signal.
     */
    private static final CopyOnWriteArrayList<LspServer> runningServers = new CopyOnWriteArrayList<>();



    /** Registers a running server. Called when the platform starts the server. */
    public static void registerServer(@NotNull LspServer server) {
        LOG.info("AntiviberLspServerListener: registering server " + server);


        if (!runningServers.contains(server)) {
            runningServers.add(server);
        }
    }

    /** Unregisters a server. Called when the server stops. */
    public static void unregisterServer(@NotNull LspServer server) {
        LOG.info("AntiviberLspServerListener: unregistering server " + server);
        runningServers.remove(server);
    }

    /**
     * Returns the tracked Antiviber LSP servers. Entries for servers that
     * have stopped in the meantime are not filtered out here; the senders
     * remove them via unregisterServer as soon as a notification to them
     * fails, which is the only liveness check that does not depend on the
     * exact shape of the platform's LspServerState API.
     *
     * CopyOnWriteArrayList makes the returned snapshot safe to iterate
     * while the list is being modified.
     */
    public static List<LspServer> getRunningServers() {
        return List.copyOf(runningServers);
    }

    /**
     * Handles custom notifications sent by the LSP server.
     * This is the platform hook for notifications that are not part of the core LSP spec.
     */
    public void handleNotification(@NotNull String method, @NotNull JsonElement params, @NotNull LspServer server) {
        LOG.info("AntiviberLspServerListener.handleNotification: " + method);
        if (!"antiviber/hints".equals(method)) {
            return;
        }

        try {
            JsonObject root = params.getAsJsonObject();
            String uri = root.has("uri") ? root.get("uri").getAsString() : null;
            if (uri == null || uri.isEmpty()) {
                LOG.warn("antiviber/hints notification missing uri field");
                return;
            }
            String markdown = root.has("markdown") ? root.get("markdown").getAsString() : "";
            LOG.info("Updating analysis from antiviber/hints notification for " + uri);
            HintsService.updateMarkdownForUri(uri, markdown);
        }
        catch (Exception e) {
            LOG.error("Failed to parse antiviber/hints notification", e);
        }
    }
}

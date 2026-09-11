package ir.vmessenger.node

import org.slf4j.LoggerFactory
import java.io.File

object NodeMain {
    /** Port of the raw-TCP dev node (`--tcp`) when `VMESSENGER_NODE_PORT` is unset. */
    private const val TCP_DEFAULT_PORT = 46555

    @JvmStatic
    fun main(args: Array<String>) {
        val log = LoggerFactory.getLogger(NodeMain::class.java)
        val useTcp = args.contains("--tcp")
        val env: Map<String, String> = System.getenv()
        var cfg = NodeConfig.fromEnv(env)
        if (useTcp && env["VMESSENGER_NODE_PORT"].isNullOrBlank()) {
            cfg = cfg.copy(port = TCP_DEFAULT_PORT)
        }
        log.info("node_config mode={} {}", if (useTcp) "tcp" else "relay", cfg.describe())
        val identity = NodeIdentity.loadOrCreate(File(cfg.stateDir))
        log.info("node_identity nodeId={} ephemeral={}", identity.nodeIdHex, identity.ephemeral)
        val state = RelayNodeState(cfg, identity.nodeId)
        if (useTcp) {
            TcpDhtNodeServer(cfg.port, state.dht).start()
        } else {
            RelayNodeServer(state).start()
        }
    }
}

package ir.vmessenger.node

object NodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val useTcp = args.contains("--tcp")
        val port = System.getenv("VMESSENGER_NODE_PORT")?.toIntOrNull()
            ?: if (useTcp) 46555 else 8443
        val publicHost = System.getenv("VMESSENGER_PUBLIC_HOST") ?: "relay.vmessenger.ir"
        if (useTcp) {
            val handler = DhtRequestHandler(port = port, publicHost = publicHost)
            TcpDhtNodeServer(port, handler).start()
        } else {
            RelayNodeServer(port = port, publicHost = publicHost).start()
        }
    }
}

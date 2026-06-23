package ir.vmessenger.node

object NodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("VMESSENGER_NODE_PORT")?.toIntOrNull() ?: 46555
        DhtNodeServer(port).start()
    }
}

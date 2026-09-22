package ir.vmessenger.data.wipe

/**
 * Cancels the app's periodic background work.
 *
 * An interface because WorkManager lives with the workers themselves, in `:app`, and because the
 * wipe plan is tested off-device. It matters to the wipe specifically: the keep-alive work exists to
 * bring messaging *back*, so leaving it enqueued while storage is being deleted means something can
 * restart the network mid-wipe, against a database that is on its way out.
 */
interface BackgroundWorkControl {
    fun cancelAll()
}

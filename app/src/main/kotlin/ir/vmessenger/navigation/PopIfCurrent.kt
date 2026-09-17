package ir.vmessenger.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/**
 * Pops [entry] while it is still the destination on top, and does nothing once it is not.
 *
 * A bare `popBackStack()` removes whatever is on top at the moment it runs. Screens close themselves
 * from gestures and effects, and those can ask more than once: the image viewer's drag-to-dismiss
 * asked on every frame past its threshold, and a scan result could land while the scanner's exit
 * transition was already running. Each extra call took the screen underneath with it — the
 * conversation, then the tabs — until the NavHost was empty and the window showed nothing, white or
 * black, with no back left to press. Keyed to the entry, a repeat is harmless. System back never
 * had this problem: the NavHost only handles it while more than one destination is stacked.
 */
internal fun NavHostController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

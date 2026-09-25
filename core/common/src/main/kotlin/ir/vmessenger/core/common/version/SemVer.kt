package ir.vmessenger.core.common.version

/** Matches `1.2.3`, `v1.2.3`, `v1.2.3-rc1`; the digit bound keeps [String.toInt] from overflowing. */
private val TAG_PATTERN =
    Regex("^v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

/**
 * The slice of semver our versions use: the app's, the node's, and the node a server runs.
 *
 * Ordering is standard semver, so `1.0.0-rc1` sorts below `1.0.0`. (Restored from the removed
 * updater; "Your servers" compares a server's node with the one the app carries.)
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String?,
) : Comparable<SemVer> {
    val isPreRelease: Boolean get() = preRelease != null

    override fun compareTo(other: SemVer): Int {
        val core = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
        return if (core != 0) core else comparePreRelease(preRelease, other.preRelease)
    }

    /** The marketing version, i.e. the tag without its leading `v`. */
    override fun toString(): String = "$major.$minor.$patch" + preRelease?.let { "-$it" }.orEmpty()

    companion object {
        fun parse(value: String): SemVer? {
            val match = TAG_PATTERN.matchEntire(value.trim()) ?: return null
            val groups = match.groupValues
            return SemVer(
                major = groups[1].toInt(),
                minor = groups[2].toInt(),
                patch = groups[3].toInt(),
                preRelease = groups[4].ifEmpty { null },
            )
        }
    }
}

/** Absent prerelease wins: `1.0.0` is newer than `1.0.0-rc1` (semver §11.3). */
private fun comparePreRelease(left: String?, right: String?): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    else -> compareIdentifiers(left.split('.'), right.split('.'))
}

private fun compareIdentifiers(left: List<String>, right: List<String>): Int {
    repeat(maxOf(left.size, right.size)) { index ->
        val result = compareIdentifier(left.getOrNull(index), right.getOrNull(index))
        if (result != 0) return result
    }
    return 0
}

/** A missing identifier sorts first (`rc` < `rc.1`); numbers compare numerically, the rest as text. */
private fun compareIdentifier(left: String?, right: String?): Int {
    val leftNumber = left?.toLongOrNull()
    val rightNumber = right?.toLongOrNull()
    return when {
        left == null || right == null -> compareValues(left != null, right != null)
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}

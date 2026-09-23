package ir.vmessenger.data.repository

import ir.vmessenger.core.common.text.BidiText
import ir.vmessenger.core.common.text.VmLocale

/**
 * The system lines stored in a group's history, in the language the app is using when the event
 * happens.
 *
 * These are the one kind of user-facing text that has to be built in the data layer: a membership
 * line is *persisted* as a message body, so it needs a concrete string at write time rather than a
 * resource resolved at render time. That also means a line keeps the language it was written in;
 * switching the app's language changes the lines that come after, not the ones already there. The
 * text never leaves the device — each member writes its own lines from the structured control.
 *
 * The name is isolated here, at write time, precisely because these lines are stored: by the time
 * one is rendered it is a finished sentence with no seam left to wrap. Several templates put the
 * name first, so without this a Latin name would make a whole Persian line left-to-right.
 */
object GroupEventText {
    val REMOVED_ME: String get() = pick(en = "You were removed from the group", fa = "شما از گروه حذف شدید")

    val LEFT_BY_ME: String get() = pick(en = "You left the group", fa = "شما گروه را ترک کردید")

    fun created(name: String): String =
        pick(en = "Group “${isolate(name)}” was created", fa = "گروه «${isolate(name)}» ساخته شد")

    fun renamed(name: String): String =
        pick(en = "The group was renamed to “${isolate(name)}”", fa = "نام گروه به «${isolate(name)}» تغییر کرد")

    fun added(name: String): String = pick(
        en = "${isolate(name)} was added to the group",
        fa = "${isolate(name)} به گروه اضافه شد",
    )

    fun removed(name: String): String =
        pick(en = "${isolate(name)} was removed from the group", fa = "${isolate(name)} از گروه حذف شد")

    fun left(name: String): String = pick(
        en = "${isolate(name)} left the group",
        fa = "${isolate(name)} گروه را ترک کرد",
    )

    fun closed(name: String): String =
        pick(en = "The group “${isolate(name)}” was closed", fa = "گروه «${isolate(name)}» بسته شد")

    fun promoted(name: String): String = pick(
        en = "${isolate(name)} is now a group admin",
        fa = "${isolate(name)} مدیر گروه شد",
    )

    fun demoted(name: String): String =
        pick(en = "${isolate(name)} is no longer a group admin", fa = "${isolate(name)} دیگر مدیر گروه نیست")

    /**
     * Written into the group's own history, so switching the policy is an event every member sees
     * in the conversation — not only a banner they might scroll past.
     */
    val AUDIT_RETENTION_ON: String
        get() = pick(
            en = "Group admins can now review edited and deleted messages",
            fa = "بازبینی پیام‌های ویرایش‌شده و حذف‌شده توسط مدیران گروه فعال شد",
        )

    val AUDIT_RETENTION_OFF: String
        get() = pick(
            en = "Review of edited and deleted messages was turned off, and what had been kept was erased",
            fa = "بازبینی پیام‌های ویرایش‌شده و حذف‌شده غیرفعال شد و آنچه نگه داشته شده بود پاک شد",
        )

    private fun pick(en: String, fa: String): String = if (VmLocale.current == VmLocale.En) en else fa

    private fun isolate(name: String): String = BidiText.isolate(name)
}

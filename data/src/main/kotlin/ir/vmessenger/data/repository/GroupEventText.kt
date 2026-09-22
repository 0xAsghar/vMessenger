package ir.vmessenger.data.repository

import ir.vmessenger.core.common.text.BidiText

/**
 * The Persian system lines stored in a group's history.
 *
 * These are the one kind of user-facing text that has to be built in the data
 * layer: a membership line is *persisted* as a message body, so it needs a
 * concrete string at write time rather than a resource resolved at render time.
 * The app ships a single locale, so nothing here is ever re-rendered in another.
 *
 * The name is isolated here, at write time, precisely because these lines are stored: by the time
 * one is rendered it is a finished sentence with no seam left to wrap. Three of the templates put
 * the name first, so without this a Latin name would make the whole Persian line left-to-right.
 * Rows written before this existed are covered instead by the renderer, which pins the paragraph
 * direction for system lines.
 */
object GroupEventText {
    const val REMOVED_ME = "شما از گروه حذف شدید"
    const val LEFT_BY_ME = "شما گروه را ترک کردید"

    fun created(name: String): String = "گروه «${isolate(name)}» ساخته شد"

    fun renamed(name: String): String = "نام گروه به «${isolate(name)}» تغییر کرد"

    fun added(name: String): String = "${isolate(name)} به گروه اضافه شد"

    fun removed(name: String): String = "${isolate(name)} از گروه حذف شد"

    fun left(name: String): String = "${isolate(name)} گروه را ترک کرد"

    fun closed(name: String): String = "گروه «${isolate(name)}» بسته شد"

    fun promoted(name: String): String = "${isolate(name)} مدیر گروه شد"

    fun demoted(name: String): String = "${isolate(name)} دیگر مدیر گروه نیست"

    /**
     * Written into the group's own history, so switching the policy is an event every member sees
     * in the conversation — not only a banner they might scroll past.
     */
    const val AUDIT_RETENTION_ON = "بازبینی پیام‌های ویرایش‌شده و حذف‌شده توسط مدیران گروه فعال شد"

    const val AUDIT_RETENTION_OFF =
        "بازبینی پیام‌های ویرایش‌شده و حذف‌شده غیرفعال شد و آنچه نگه داشته شده بود پاک شد"

    private fun isolate(name: String): String = BidiText.isolate(name)
}

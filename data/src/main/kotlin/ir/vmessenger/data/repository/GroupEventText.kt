package ir.vmessenger.data.repository

/**
 * The Persian system lines stored in a group's history.
 *
 * These are the one kind of user-facing text that has to be built in the data
 * layer: a membership line is *persisted* as a message body, so it needs a
 * concrete string at write time rather than a resource resolved at render time.
 * The app ships a single locale, so nothing here is ever re-rendered in another.
 */
object GroupEventText {
    const val REMOVED_ME = "شما از گروه حذف شدید"
    const val LEFT_BY_ME = "شما گروه را ترک کردید"

    fun created(name: String): String = "گروه «$name» ساخته شد"

    fun renamed(name: String): String = "نام گروه به «$name» تغییر کرد"

    fun added(name: String): String = "$name به گروه اضافه شد"

    fun removed(name: String): String = "$name از گروه حذف شد"

    fun left(name: String): String = "$name گروه را ترک کرد"

    fun closed(name: String): String = "گروه «$name» بسته شد"
}

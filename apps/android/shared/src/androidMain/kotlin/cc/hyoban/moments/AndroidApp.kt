package cc.hyoban.moments

import android.content.Context

/** The application's context, for the shared code's Android side where no caller has one to hand; set as the app starts. */
object AndroidApp {
    lateinit var context: Context
}

package cc.hyoban.moments

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The language the app speaks: the phone's, or Chinese or English whatever the phone's is. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * The language chosen for the app. On Android 13 and later it is the system's
         * per-app language, which ColorOS and others may not offer in their own settings;
         * before, the app keeps it itself.
         */
        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                prefs(context).getString(KEY, null)
            }
            return fromTag(tag)
        }

        /**
         * Speaks [language] from now on: the system brings the open activity back in it on
         * Android 13 and later; before, [activity] is made again in it.
         */
        fun change(activity: Activity, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag.orEmpty())
            } else {
                prefs(activity).edit().putString(KEY, language.tag).apply()
                activity.recreate()
            }
        }

        /** [base] speaking the language the app keeps, before Android 13, where the system can't. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = prefs(base).getString(KEY, null) ?: return base
            val locale = Locale.forLanguageTag(tag)
            // The dates read Locale.getDefault(), so it speaks the same.
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }

        private fun fromTag(tag: String?): AppLanguage = when {
            tag == null -> SYSTEM
            tag.startsWith("zh") -> CHINESE
            tag.startsWith("en") -> ENGLISH
            else -> SYSTEM
        }

        private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        private const val KEY = "language"
    }
}

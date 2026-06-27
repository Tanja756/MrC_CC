package com.mrc.warehouse.api

import android.content.Context
import java.util.Properties

/**
 * Загружает конфигурацию для внешнего сайта из assets/cl_x23.properties.
 *
 * Использование:
 *   val config = ClX23Config.load(context)
 *   val baseUrl = config.baseUrl
 */
class ClX23Config private constructor(
    val baseUrl: String
) {
    companion object {
        private const val FILE_NAME = "cl_x23.properties"
        private const val DEFAULT_URL = ""

        @Volatile
        private var instance: ClX23Config? = null

        /**
         * Загружает конфиг. При ошибке возвращает конфиг с URL по умолчанию.
         */
        fun load(context: Context): ClX23Config {
            instance?.let { return it }

            synchronized(this) {
                instance?.let { return it }

                val baseUrl = try {
                    val props = Properties()
                    context.assets.open(FILE_NAME).use { stream ->
                        props.load(stream)
                    }
                    props.getProperty("base_url", DEFAULT_URL).trimEnd('/')
                } catch (e: Exception) {
                    DEFAULT_URL
                }

                return ClX23Config(baseUrl).also { instance = it }
            }
        }
    }
}
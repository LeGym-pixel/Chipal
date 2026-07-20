package com.svpn.app

import android.app.Application
import com.svpn.app.data.SvpnRepository
import com.svpn.app.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SvpnApplication : Application() {

    lateinit var repository: SvpnRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SvpnRepository(this)

        // Apply previously saved language before any UI is shown.
        CoroutineScope(Dispatchers.IO).launch {
            val lang = repository.language.first()
            LocaleHelper.apply(lang)
        }
    }
}

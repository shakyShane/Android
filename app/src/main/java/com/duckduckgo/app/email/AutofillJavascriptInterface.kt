/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.email

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.duckduckgo.app.browser.DuckDuckGoUrlDetector
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.feature.toggles.api.FeatureToggle
import com.duckduckgo.privacy.config.api.Autofill
import com.duckduckgo.privacy.config.api.PrivacyFeatureName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking

interface AutofillResponse {
    fun response(): String
}

class AutofillJavascriptInterface(
    private val emailManager: EmailManager,
    private val webView: WebView,
    private val urlDetector: DuckDuckGoUrlDetector,
    private val dispatcherProvider: DispatcherProvider,
    private val featureToggle: FeatureToggle,
    private val autofill: Autofill,
    private val showNativeTooltip: (input: Input) -> Unit
) {

    private fun getUrl(): String? {
        return runBlocking(dispatcherProvider.main()) {
            webView.url
        }
    }

    private fun isUrlFromDuckDuckGoEmail(): Boolean {
        val url = getUrl()
        return (url != null && urlDetector.isDuckDuckGoEmailUrl(url))
    }

    private fun isFeatureEnabled() = featureToggle.isFeatureEnabled(PrivacyFeatureName.AutofillFeatureName, defaultValue = true) ?: false

    @JavascriptInterface
    fun getRuntimeConfiguration(): String {
        return """{
  "success": {
    "contentScope": {
      "features": {
        "autofill": {
          "state": "enabled",
          "exceptions": []
        }
      },
      "unprotectedTemporary": []
    },
    "userUnprotectedDomains": [],
    "userPreferences": {
      "debug": false,
      "platform": {
        "name": "android"
      },
      "features": {
        "autofill": {
          "settings": {
            "featureToggles": {
              "inputType_credentials": true,
              "inputType_identities": false,
              "inputType_creditCards": false,
              "emailProtection": true,
              "password_generation": false,
              "credentials_saving": true
            }
          }
        }
      }
    }
  }
}"""
        // return if (isUrlFromDuckDuckGoEmail()) {
        //     emailManager.isSignedIn().toString()
        // } else {
        //     ""
        // }
    }

    @JavascriptInterface
    fun getAvailableInputTypes(): String {
        //language=JSON
        return """{
  "success": {
    "email": true
  }
}"""
    }

    @JavascriptInterface
    fun getAutofillData(input: String): String {
        return try {
            val moshi: Moshi = Moshi.Builder().build()
            val adapter: JsonAdapter<Input> = moshi.adapter(Input::class.java)
            val inputType = adapter.fromJson(input)
            inputType?.let { this.showNativeTooltip(it) }
            "{}"
        } catch (e: Exception) {
            "{}"
        }
    }

    data class Input(
        val type: String,
        val mainType: String,
        val subType: String
    )

    companion object {
        const val AUTOFILL_INTERFACE_NAME = "BrowserAutofill"
    }
}

data class AutofillDataResponse (
    val success: AutofillResponse,
    val type: String = "getAutofillDataResponse"
): AutofillResponse {
    override fun response(): String {
        val inner = this.success.response();
        return """{"type": "${this.type}", "success": ${inner}}"""
    }
}

data class AutofillError (
    val error: String = "unknown error",
): AutofillResponse {
    override fun response(): String {
        val moshi: Moshi = Moshi.Builder().build()
        val adapter: JsonAdapter<AutofillError> = moshi.adapter(AutofillError::class.java)
        return adapter.toJson(this)
    }
};

data class Credentials (
    val username: String = "example@duck.com",
    val password: String = "123456",
): AutofillResponse {
    override fun response(): String {
        val moshi: Moshi = Moshi.Builder().build()
        val adapter: JsonAdapter<Credentials> = moshi.adapter(Credentials::class.java)
        return adapter.toJson(this)
    }
};

/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.survey.api

data class SurveyGroup(
    val id: String,
    val surveyOptions: List<SurveyOption>
) {

    data class SurveyOption(
        val url: String,
        val installationDay: Int?,
        val ratioOfUsersToShow: Double,
        val isEmailSignedInRequired: Boolean?,
        val isAtpEverEnabledRequired: Boolean?,
        val isAtpWaitlistRequired: Boolean?,
        val urlParameters: List<String>?
    )
}

sealed class SurveyUrlParameter(val parameter: String) {
    object EmailCohortParam : SurveyUrlParameter("cohort")
    object AtpCohortParam : SurveyUrlParameter("atp_cohort")
    object Android12Param : SurveyUrlParameter("android12")
    object AtbCohortParam : SurveyUrlParameter("atb_cohort")
}

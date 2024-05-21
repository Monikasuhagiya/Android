/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.newtabpage.impl

import android.content.Context
import android.view.View
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.newtabpage.api.FocusedViewPlugin
import com.duckduckgo.newtabpage.api.FocusedViewVersion
import com.duckduckgo.newtabpage.impl.view.FocusedView
import com.squareup.anvil.annotations.ContributesMultibinding
import javax.inject.Inject

@ContributesMultibinding(
    scope = ActivityScope::class,
    boundType = FocusedViewPlugin::class,
)
class FocusedPage @Inject constructor() : FocusedViewPlugin {

    override val name: String = FocusedViewVersion.NEW.name
    override fun getView(context: Context): View {
        return FocusedView(context)
    }
}

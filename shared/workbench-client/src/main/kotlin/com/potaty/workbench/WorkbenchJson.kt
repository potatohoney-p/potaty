/*
 * Copyright (c) 2026, Potaty
 *
 * Shared JSON codec for the workbench client: lenient on unknown keys (forward-compatible with
 * backend additions), omits nulls/defaults to keep request bodies small.
 */

package com.potaty.workbench

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal val WorkbenchJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

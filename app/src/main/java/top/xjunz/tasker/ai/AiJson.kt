/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai

import kotlinx.serialization.json.Json

val AiJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

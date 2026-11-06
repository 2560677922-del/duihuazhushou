package com.dbpg.duihuazhushou.character

import com.dbpg.stcore.character.CharacterCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把原生解析出来的卡还原成 ST V2 JSON，交给网页侧导入。
 *
 * 为什么要绕这一圈：网页的角色库存在 IndexedDB 里，原生读不到；
 * 而网页的 `assets.importCharacter()` 已经处理好建档/存档/选中。
 * 传标准 V2 JSON 过去，等于让网页"重新导入一次"，链路最短、最不容易错。
 */
object CharacterJson {

    fun toV2(card: CharacterCard): String {
        val data = JSONObject()
            .put("name", card.name)
            .put("description", card.description)
            .put("personality", card.personality)
            .put("scenario", card.scenario)
            .put("first_mes", card.firstMessage)
            .put("mes_example", card.messageExample)
            .put("creator_notes", card.creatorNotes)
            .put("system_prompt", card.systemPrompt)
            .put("post_history_instructions", card.postHistoryInstructions)
            .put("alternate_greetings", JSONArray(card.alternateGreetings))
            .put("tags", JSONArray(card.tags))
            .put("creator", card.creator)
            .put("character_version", card.characterVersion)

        return JSONObject()
            .put("spec", "chara_card_v2")
            .put("spec_version", "2.0")
            .put("data", data)
            .toString()
    }
}

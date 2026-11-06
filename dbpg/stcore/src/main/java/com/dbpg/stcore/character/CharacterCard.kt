package com.dbpg.stcore.character

/**
 * SillyTavern 角色卡（只读模型）。
 *
 * 这里**只解析、不改写**：字段命名与 ST 的 V2/V3 规范一一对应，
 * 保证从网页版导出的角色卡能原样落到原生端，反之亦然。
 *
 * 三类来源统一收敛成这个结构：
 *  1. V2/V3 JSON：`{"spec":"chara_card_v2","data":{...}}`
 *  2. V1 JSON：顶层直接就是 name/description/first_mes...
 *  3. PNG 角色卡：图片 tEXt/zTXt/iTXt 里塞的 base64 JSON（见 [CharacterCardParser]）
 */
data class CharacterCard(
    /** 空串表示 V1 老卡 */
    val spec: String = "",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    /** 开场白 */
    val firstMessage: String = "",
    /** 对话示例 */
    val messageExample: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    /** 备用开场白，ST 的 swipes 就靠它 */
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    /** 头像图片的原始字节（PNG 卡才有），用于原生列表显示 */
    val avatarBytes: ByteArray? = null,
) {
    /** 开场白候选：first_mes 打头，后面跟 alternate_greetings。 */
    val greetings: List<String>
        get() = (listOf(firstMessage) + alternateGreetings).filter { it.isNotBlank() }

    /** 列表页副标题：优先 persona 简介，再退到 scenario，都没有就显示版本号。 */
    val subtitle: String
        get() = when {
            personality.isNotBlank() -> personality.lineSequence().first()
            scenario.isNotBlank() -> scenario.lineSequence().first()
            characterVersion.isNotBlank() -> "v$characterVersion"
            else -> ""
        }

    // avatarBytes 是数组，data class 默认的 equals 对数组是引用比较，这里显式改写
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacterCard) return false
        return spec == other.spec && name == other.name &&
            description == other.description && personality == other.personality &&
            scenario == other.scenario && firstMessage == other.firstMessage &&
            messageExample == other.messageExample && creatorNotes == other.creatorNotes &&
            systemPrompt == other.systemPrompt &&
            postHistoryInstructions == other.postHistoryInstructions &&
            alternateGreetings == other.alternateGreetings && tags == other.tags &&
            creator == other.creator && characterVersion == other.characterVersion
    }

    override fun hashCode(): Int {
        var result = spec.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + firstMessage.hashCode()
        result = 31 * result + alternateGreetings.hashCode()
        result = 31 * result + tags.hashCode()
        return result
    }
}

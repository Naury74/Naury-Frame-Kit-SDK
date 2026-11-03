package com.naury.framekit.core.overlay

/**
 * 스티커로 제공하는 표준 이모지. 키보드처럼 그룹으로 묶는다. 기기 이모지 폰트로 렌더링하므로
 * 이미지 에셋을 포함하지 않으며, 모양은 기기(Noto, Samsung 등)를 따른다.
 */
public object EmojiCatalog {

    public enum class Category {
        SMILEYS,
        GESTURES,
        HEARTS,
        ANIMALS,
        FOOD,
        ACTIVITIES,
        TRAVEL,
        OBJECTS,
        SYMBOLS,
    }

    public data class Group(val category: Category, val emoji: List<String>)

    public const val ASSET_PREFIX: String = "emoji:"

    public val groups: List<Group> = listOf(
        Group(Category.SMILEYS, split("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🤓 🥳 😏 😌 😴 🤔 🤫 🤭 😮 😲 🥺 😢 😭 😤 😡 🤯 😱 🥶 🥵 😷 🤒 🤠 👻 💀 🤖 👽 💩")),
        Group(Category.GESTURES, split("👍 👎 👏 🙌 👐 🤝 🙏 ✌️ 🤞 🤟 🤘 👌 🤌 👈 👉 👆 👇 ☝️ ✋ 🤚 👋 💪 ✍️ 💅")),
        Group(Category.HEARTS, split("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💯 💢 💥 💫 💦 💤")),
        Group(Category.ANIMALS, split("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦄 🐝 🦋 🐢 🐙 🐬 🐳 🌸 🌷 🌹 🌻 🌈 ⭐ 🌙 ☀️ ⛅ ❄️ 🔥 💧")),
        Group(Category.FOOD, split("🍎 🍓 🍒 🍑 🍉 🍋 🍌 🍇 🍔 🍟 🍕 🌭 🌮 🍜 🍣 🍙 🍰 🎂 🍩 🍪 🍫 🍭 🍦 ☕ 🧋 🍺 🍷 🥂")),
        Group(Category.ACTIVITIES, split("⚽ 🏀 🏈 ⚾ 🎾 🏐 🎳 🏓 🏸 🥊 🎮 🎯 🎨 🎬 🎤 🎧 🎸 🎹 🏆 🥇 🎉 🎊 🎈 🎁")),
        Group(Category.TRAVEL, split("🚗 🚕 🚌 🚓 🚑 🚒 🏍️ 🚲 ✈️ 🚀 🚢 ⛵ 🏠 🏢 🏰 🗼 🗽 ⛰️ 🏖️ 🏝️ 🎡 🎢 🌍 📍")),
        Group(Category.OBJECTS, split("📷 📸 📱 💻 ⌚ 💡 📚 ✏️ 📌 📎 ✂️ 🔑 🎀 💍 💎 👑 🎩 👓 🕶️ 👟 👜 🛍️ 💰 ⏰")),
        Group(Category.SYMBOLS, split("✅ ❌ ❗ ❓ ⚠️ 🚫 ♻️ ✨ 🎵 🎶 ➕ ➖ ➡️ ⬅️ ⬆️ ⬇️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪")),
    )

    public fun assetId(emoji: String): String = ASSET_PREFIX + emoji

    /** 이모지 에셋 id의 이모지 텍스트. 다른 에셋이면 `null`. */
    public fun emojiOf(assetId: String): String? = assetId.takeIf { it.startsWith(ASSET_PREFIX) }?.removePrefix(ASSET_PREFIX)?.takeIf { it.isNotEmpty() }

    private fun split(list: String): List<String> = list.split(' ').filter { it.isNotBlank() }
}

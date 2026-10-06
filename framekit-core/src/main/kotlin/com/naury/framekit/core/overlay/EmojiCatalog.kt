package com.naury.framekit.core.overlay

/**
 * Standard emoji offered as stickers, grouped like a keyboard. They are rendered with the device emoji
 * font, so no image assets are bundled; the look follows the device (Noto, Samsung and so on).
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

    /** The emoji text of an emoji asset id, or `null` for other assets. */
    public fun emojiOf(assetId: String): String? = assetId.takeIf { it.startsWith(ASSET_PREFIX) }?.removePrefix(ASSET_PREFIX)?.takeIf { it.isNotEmpty() }

    private fun split(list: String): List<String> = list.split(' ').filter { it.isNotBlank() }
}

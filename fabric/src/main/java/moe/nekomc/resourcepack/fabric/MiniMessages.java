package moe.nekomc.resourcepack.fabric;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * MiniMessage 的极小子集：只认本项目文案实际用到的标签。
 *
 * <p>Fabric 侧没有 Adventure，内核又统一输出 MiniMessage 文本；与其为一个端口引入一整套
 * 序列化库，不如在这里把手写文案转成原版组件。支持颜色、粗体/斜体/下划线/删除线、
 * reset、click:run_command / click:suggest_command，以及 {@code \<} 转义。
 * 不认识的标签按格式控制丢弃，不会把尖括号显示给玩家。</p>
 */
public final class MiniMessages {
    private MiniMessages() {
    }

    /** 把带 MiniMessage 标签的文本转成原版组件。 */
    static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        MutableComponent root = Component.empty();
        StringBuilder buffer = new StringBuilder();
        Deque<Style> styles = new ArrayDeque<>();
        styles.push(Style.EMPTY);
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length() && text.charAt(index + 1) == '<') {
                // 内核在拼接玩家可控文本时会把 < 转义成 \<。
                buffer.append('<');
                index += 2;
                continue;
            }
            if (current != '<') {
                buffer.append(current);
                index++;
                continue;
            }
            int end = text.indexOf('>', index);
            if (end < 0) {
                buffer.append(text.substring(index));
                break;
            }
            String raw = text.substring(index + 1, end).trim();
            String tag = raw.toLowerCase(Locale.ROOT);
            Style next = styleFor(tag, raw, styles.peek());
            if (next == null) {
                // 未知开标签（<hover:...> 等）只是不生效，仍要占一个栈位；
                // 否则它后面的 </xxx> 会误弹掉一层真实样式，后续文字跟着掉色。
                styles.push(styles.peek());
                index = end + 1;
                continue;
            }
            if (next != null) {
                flush(root, buffer, styles.peek());
                styles.push(next);
                index = end + 1;
                continue;
            }
            if (tag.startsWith("/")) {
                flush(root, buffer, styles.peek());
                if (styles.size() > 1) {
                    styles.pop();
                }
                index = end + 1;
                continue;
            }
            index = end + 1;
        }
        flush(root, buffer, styles.peek());
        return root;
    }

    private static Style styleFor(String tag, String raw, Style base) {
        if (tag.equals("reset")) {
            return Style.EMPTY;
        }
        ChatFormatting color = formatting(tag);
        if (color != null) {
            return base.applyFormat(color);
        }
        return switch (tag) {
            case "bold", "b" -> base.applyFormat(ChatFormatting.BOLD);
            case "!bold", "!b" -> base.withBold(false);
            case "italic", "i", "em" -> base.applyFormat(ChatFormatting.ITALIC);
            case "!italic", "!i", "!em" -> base.withItalic(false);
            case "underlined", "u" -> base.withUnderlined(true);
            case "!underlined", "!u" -> base.withUnderlined(false);
            case "strikethrough", "st" -> base.applyFormat(ChatFormatting.STRIKETHROUGH);
            case "!strikethrough", "!st" -> base.withStrikethrough(false);
            case "obfuscated", "obf" -> base.applyFormat(ChatFormatting.OBFUSCATED);
            case "!obfuscated", "!obf" -> base.withObfuscated(false);
            default -> clickStyle(tag, raw, base);
        };
    }

    private static Style clickStyle(String tag, String raw, Style base) {
        if (!tag.startsWith("click:")) {
            return null;
        }
        String body = raw.substring(raw.indexOf(':') + 1).trim();
        int colon = body.indexOf(':');
        String action = (colon < 0 ? body : body.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String value = unquote(colon < 0 ? "" : body.substring(colon + 1).trim());
        if (value.isEmpty()) {
            return null;
        }
        return switch (action) {
            case "run_command" -> base.withClickEvent(new ClickEvent.RunCommand(value));
            case "suggest_command" -> base.withClickEvent(new ClickEvent.SuggestCommand(value));
            default -> null;
        };
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static ChatFormatting formatting(String tag) {
        return switch (tag) {
            case "black" -> ChatFormatting.BLACK;
            case "dark_blue" -> ChatFormatting.DARK_BLUE;
            case "dark_green" -> ChatFormatting.DARK_GREEN;
            case "dark_aqua" -> ChatFormatting.DARK_AQUA;
            case "dark_red" -> ChatFormatting.DARK_RED;
            case "dark_purple" -> ChatFormatting.DARK_PURPLE;
            case "gold" -> ChatFormatting.GOLD;
            case "gray", "grey" -> ChatFormatting.GRAY;
            case "dark_gray", "dark_grey" -> ChatFormatting.DARK_GRAY;
            case "blue" -> ChatFormatting.BLUE;
            case "green" -> ChatFormatting.GREEN;
            case "aqua" -> ChatFormatting.AQUA;
            case "red" -> ChatFormatting.RED;
            case "light_purple" -> ChatFormatting.LIGHT_PURPLE;
            case "yellow" -> ChatFormatting.YELLOW;
            case "white" -> ChatFormatting.WHITE;
            default -> null;
        };
    }

    private static void flush(MutableComponent root, StringBuilder buffer, Style style) {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString();
        buffer.setLength(0);
        root.append(Component.literal(text).setStyle(style));
    }
}

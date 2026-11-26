package moe.nekomc.resourcepack.fabric;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * MiniMessage 的极小子集：只认本项目命令文案实际用到的那几个颜色标签。
 *
 * <p>Fabric 侧没有 Adventure，内核又统一输出 MiniMessage 文本；与其为一个端口引入
 * 一整套序列化库，不如在这里把手写文案转成原版组件。标签不认识就原样保留，
 * 出错时最多是多个尖括号，不会丢内容。</p>
 */
public final class MiniMessages {

    private MiniMessages() {
    }

    /** 把带 MiniMessage 标签的文本转成原版组件。 */
    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        MutableComponent root = Component.empty();
        StringBuilder buffer = new StringBuilder();
        ChatFormatting color = null;
        boolean bold = false;
        Style style = Style.EMPTY;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '<') {
                int end = text.indexOf('>', index);
                if (end > index) {
                    String tag = text.substring(index + 1, end).toLowerCase();
                    ChatFormatting next = formatting(tag);
                    if (next != null || tag.equals("reset") || tag.equals("bold") || tag.equals("!bold")) {
                        flush(root, buffer, color, bold, style);
                        if (tag.equals("reset")) {
                            color = null;
                            bold = false;
                            style = Style.EMPTY;
                        } else if (tag.equals("bold")) {
                            bold = true;
                        } else if (tag.equals("!bold")) {
                            bold = false;
                        } else {
                            color = next;
                        }
                        index = end + 1;
                        continue;
                    }
                    // 不认识的标签（click / hover / 闭合标签）只当格式控制，丢掉不显字符。
                    index = end + 1;
                    continue;
                }
            }
            buffer.append(current);
            index++;
        }
        flush(root, buffer, color, bold, style);
        return root;
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

    private static void flush(MutableComponent root, StringBuilder buffer, ChatFormatting color, boolean bold, Style style) {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString();
        buffer.setLength(0);
        Style applied = style;
        if (color != null) {
            applied = applied.applyFormat(color);
        }
        if (bold) {
            applied = applied.applyFormat(ChatFormatting.BOLD);
        }
        root.append(Component.literal(text).setStyle(applied));
    }
}

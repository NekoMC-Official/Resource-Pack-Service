package moe.nekomc.resourcepack.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.List;
import moe.nekomc.resourcepack.command.RpsCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 把内核的 {@link RpsCommand} 挂到 Brigadier 上：一条 /rps，后面整段当参数。
 *
 * <p>补全也走内核：参数原样切分后交给 {@link RpsCommand#complete}，
 * 这样 Paper 与 Fabric 的子命令表只有一份。</p>
 */
public final class FabricCommands {

    private FabricCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FabricPlatform platform) {
        RpsCommand command = new RpsCommand(platform);
        dispatcher.register(Commands.literal("rps")
            .executes(context -> run(command, context, List.of()))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    FabricCommandSender sender = new FabricCommandSender(context.getSource());
                    String raw = StringArgumentType.getString(context, "args");
                    for (String candidate : command.complete(sender, tokens(raw, false))) {
                        builder.suggest(candidate);
                    }
                    return builder.buildFuture();
                })
                .executes(context -> run(command, context, tokens(StringArgumentType.getString(context, "args"), true)))));
    }

    private static int run(RpsCommand command, com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, List<String> args) {
        boolean ok = command.execute(new FabricCommandSender(context.getSource()), args);
        return ok ? 1 : 0;
    }

    /** 把整段参数切词；补全时结尾带空格就补一个空词，让内核知道在起新词。 */
    private static List<String> tokens(String raw, boolean trim) {
        String text = raw == null ? "" : raw;
        if (trim) {
            text = text.trim();
            if (text.isEmpty()) {
                return List.of();
            }
        }
        List<String> out = new ArrayList<>();
        for (String token : text.split(" ", -1)) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        if (!trim && (text.isEmpty() || text.endsWith(" "))) {
            out.add("");
        }
        return out;
    }
}

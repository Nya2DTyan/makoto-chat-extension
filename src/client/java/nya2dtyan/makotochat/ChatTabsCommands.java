package nya2dtyan.makotochat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Клиентские команды для управления вкладками.
 *
 * <p>Команды дублируют горячие клавиши: бинды игрок может случайно не назначить,
 * а {@code /chattabs} работает всегда.
 */
public final class ChatTabsCommands {

	private ChatTabsCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
			net.minecraft.commands.CommandBuildContext context) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = LiteralArgumentBuilder
				.<FabricClientCommandSource>literal("chattabs")
				.executes(source -> summary(source.getSource()));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("next")
				.executes(source -> {
					ChatTabsMod.logger().info("/chattabs next");
					ChatRouter.getInstance().cycleNext();
					return one(source.getSource());
				}));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("prev")
				.executes(source -> {
					ChatTabsMod.logger().info("/chattabs prev");
					ChatRouter.getInstance().cyclePrevious();
					return one(source.getSource());
				}));

		for (ChatChannel channel : ChatChannel.values()) {
			root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("tab")
					.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal(channel.name().toLowerCase())
							.executes(source -> {
								ChatRouter.getInstance().select(channel);
								return one(source.getSource());
							})));
		}

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("active")
						.executes(source -> {
							ChatRouter.getInstance().store().clear(ChatRouter.getInstance().activeTab());
							return one(source.getSource());
						}))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("all")
						.executes(source -> {
							ChatRouter.getInstance().store().clearAll();
							return one(source.getSource());
						})));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("overlay")
				.executes(source -> {
					ChatRouter.getInstance().toggleOverlay();
					return one(source.getSource());
				}));

		dispatcher.register(root);
	}

	private static int one(FabricClientCommandSource source) {
		return 1;
	}

	private static int summary(FabricClientCommandSource source) {
		ChatRouter router = ChatRouter.getInstance();
		StringBuilder text = new StringBuilder("Chat Tabs — активная вкладка: ")
				.append(router.activeTab().displayName());
		for (ChatChannel channel : ChatChannel.values()) {
			text.append(" | ").append(channel.displayName()).append(": ")
					.append(router.store().totalIn(channel));
		}
		ChatTabsMod.logger().info(text.toString());
		return 1;
	}
}
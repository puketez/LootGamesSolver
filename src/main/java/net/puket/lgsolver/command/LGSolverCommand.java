package net.puket.lgsolver.command;

import net.puket.lgsolver.LGSolver;
import net.puket.lgsolver.event.ClientEvents;
import net.puket.lgsolver.event.ClientEvents.BoundBoard;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.List;

public class LGSolverCommand extends CommandBase {

    private final ClientEvents client;

    public LGSolverCommand(ClientEvents client) {
        this.client = client;
    }

    @Override public String getCommandName() { return "lgsolver"; }

    @Override public String getCommandUsage(ICommandSender sender) {
        return "/lgsolver <dump|rebind>";
    }

    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return CommandBase.getListOfStringsMatchingLastWord(args, new String[] { "dump", "rebind" });
        }
        return null;
    }

    @Override public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            reply(sender, "usage: " + getCommandUsage(sender));
            return;
        }
        switch (args[0].toLowerCase()) {
            case "dump":
                dump(sender);
                break;
            case "rebind":
                LGSolver.lgAccessor.rebind();
                LGSolver.golAccessor.rebind();
                LGSolver.puzzleAccessor.rebind();
                reply(sender, "[LGSolver] rebound MS: " + LGSolver.lgAccessor.describe());
                reply(sender, "[LGSolver] rebound GOL: " + LGSolver.golAccessor.describe());
                reply(sender, "[LGSolver] rebound " + LGSolver.puzzleAccessor.describe());
                break;
            default:
                reply(sender, "unknown subcommand: " + args[0]);
        }
    }

    private void dump(ICommandSender sender) {
        BoundBoard bb = client.current();
        if (bb != null) {
            reply(sender, "[LGSolver-MS] " + bb.board.toString() + " master=("
                + bb.tx + "," + bb.ty + "," + bb.tz + ") origin=("
                + bb.originX + "," + bb.originY + "," + bb.originZ + ")");
            for (String line : bb.board.toAscii().split("\n")) reply(sender, line);
        } else {
            reply(sender, "[LGSolver-MS] no board bound");
        }
        if (client.golBound()) {
            net.puket.lgsolver.gol.GOLState gs = client.golState();
            StringBuilder sb = new StringBuilder("[LGSolver-GOL] stage=").append(gs.stageId)
                .append(" origin=(").append(gs.originX).append(",").append(gs.originY)
                .append(",").append(gs.originZ).append(") sequence=");
            for (int i = 0; i < gs.sequence.size(); i++) {
                int[] xy = gs.sequence.get(i);
                if (i > 0) sb.append(",");
                sb.append("(").append(xy[0]).append(",").append(xy[1]).append(")");
            }
            reply(sender, sb.toString());
        } else {
            reply(sender, "[LGSolver-GOL] not bound");
        }
        for (String line : client.espDebug().split("\n")) reply(sender, line);
    }

    private static void reply(ICommandSender s, String msg) {
        s.addChatMessage(new ChatComponentText(msg));
    }
}
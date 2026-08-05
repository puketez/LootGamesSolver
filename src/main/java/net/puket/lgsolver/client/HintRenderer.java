package net.puket.lgsolver.client;

import net.puket.lgsolver.LGSolver;
import net.puket.lgsolver.event.ClientEvents.BoundBoard;
import net.puket.lgsolver.solver.Hint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * Draws colored quads on top of Minesweeper cells and (optionally) probability
 * labels. All rendering uses immediate mode — trivially fast for ≤225 quads
 * per frame.
 *
 * <p>Coordinate assumption: the master TE is at the north-west corner of the
 * board (see {@code BlockSmartSubordinate}'s javadoc from upstream); cells
 * extend +x east and +z south, all at y = master.yCoord.
 */
public class HintRenderer {

    private static final double INSET = 0.06;
    private static final double LIFT  = 1.02;
    private static final double MAX_RENDER_DISTANCE_SQ = 32.0 * 32.0;

    public void render(float partialTicks, BoundBoard bb) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null) return;

        double dx = p.posX - (bb.originX + 0.5);
        double dy = p.posY - (bb.originY + 0.5);
        double dz = p.posZ - (bb.originZ + 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ) return;

        double camX = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
        double camY = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
        double camZ = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;

        // Capture, don't assume: MC uses GL_LEQUAL in some passes and GL_LESS
        // in others, and whatever draws next inherits what we leave.
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        int size = bb.board.size();
        for (Hint h : bb.hints) {
            if (h.x < 0 || h.z < 0 || h.x >= size || h.z >= size) continue;
            double x0 = bb.originX + h.x + INSET;
            double z0 = bb.originZ + h.z + INSET;
            double x1 = bb.originX + h.x + 1 - INSET;
            double z1 = bb.originZ + h.z + 1 - INSET;
            double y  = bb.originY + LIFT;

            float r, g, b, a = 0.55f;
            switch (h.kind) {
                case SAFE:
                    r = 0.2f; g = 0.9f; b = 0.3f; break;
                case MINE:
                    r = 0.95f; g = 0.2f; b = 0.2f; break;
                case GUESS:
                default:
                    float t = (float) Math.max(0.0, Math.min(1.0, h.probability));
                    // Yellow-orange gradient by risk. Low p → pale green-yellow;
                    // high p → deep orange-red.
                    r = 0.9f;
                    g = 0.9f - 0.5f * t;
                    b = 0.2f - 0.15f * t;
                    a = 0.35f + 0.2f * t;
            }

            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex3d(x0, y, z0);
            GL11.glVertex3d(x0, y, z1);
            GL11.glVertex3d(x1, y, z1);
            GL11.glVertex3d(x1, y, z0);
            GL11.glEnd();

            // Border outline for contrast.
            GL11.glColor4f(0f, 0f, 0f, 0.7f);
            GL11.glLineWidth(2.0f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex3d(x0, y + 0.001, z0);
            GL11.glVertex3d(x0, y + 0.001, z1);
            GL11.glVertex3d(x1, y + 0.001, z1);
            GL11.glVertex3d(x1, y + 0.001, z0);
            GL11.glEnd();
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthFunc(prevDepthFunc);
        GL11.glColor4f(1f, 1f, 1f, 1f); // leave no tint on whatever draws next
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();

        if (LGSolver.config.showProbabilityLabels) {
            drawProbabilityLabels(mc, bb, partialTicks);
        }
    }

    private void drawProbabilityLabels(Minecraft mc, BoundBoard bb, float partialTicks) {
        EntityClientPlayerMP p = mc.thePlayer;
        double camX = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
        double camY = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
        double camZ = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;

        boolean drewAny = false;
        for (Hint h : bb.hints) {
            if (h.kind != Hint.Kind.GUESS) continue;
            drewAny = true;
            double cx = bb.originX + h.x + 0.5 - camX;
            double cz = bb.originZ + h.z + 0.5 - camZ;
            double cy = bb.originY + 1.15 - camY;

            GL11.glPushMatrix();
            GL11.glTranslated(cx, cy, cz);
            GL11.glRotatef(-mc.thePlayer.rotationYaw, 0, 1, 0);
            GL11.glRotatef(mc.thePlayer.rotationPitch, 1, 0, 0);
            float s = 0.03f;
            GL11.glScalef(-s, -s, s);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            String label = String.format(Locale.ROOT, "%d%%", (int) Math.round(h.probability * 100));
            int w = mc.fontRenderer.getStringWidth(label);
            mc.fontRenderer.drawString(label, -w / 2, 0, 0xFFFFFFFF, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glPopMatrix();
        }
        if (drewAny) {
            // The loop enables blend per label; hand the state back clean.
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }
}

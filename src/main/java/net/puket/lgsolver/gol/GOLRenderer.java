package net.puket.lgsolver.gol;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import org.lwjgl.opengl.GL11;

/**
 * Draws only the NEXT expected cell during the waiting stage — a green tint
 * plus a "N/total" label so the player knows how far into the sequence they
 * are.
 */
public final class GOLRenderer {

    private static final double MAX_DISTANCE_SQ = 32.0 * 32.0;

    public void render(float partialTicks, GOLState state) {
        if (state == null || state.sequence.isEmpty()) return;
        int[] next = state.nextExpected();
        if (next == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null) return;

        double dx = p.posX - (state.originX + state.size * 0.5);
        double dy = p.posY - (state.originY + 0.5);
        double dz = p.posZ - (state.originZ + state.size * 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQ) return;

        double camX = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
        double camY = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
        double camZ = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;

        int gx = next[0], gz = next[1];
        double x0 = state.originX + gx + 0.08;
        double z0 = state.originZ + gz + 0.08;
        double x1 = state.originX + gx + 1 - 0.08;
        double z1 = state.originZ + gz + 1 - 0.08;
        double y  = state.originY + 1.02;

        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        GL11.glColor4f(0.2f, 0.9f, 0.3f, 0.55f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x0, y, z0);
        GL11.glVertex3d(x0, y, z1);
        GL11.glVertex3d(x1, y, z1);
        GL11.glVertex3d(x1, y, z0);
        GL11.glEnd();

        GL11.glColor4f(0f, 0f, 0f, 0.7f);
        GL11.glLineWidth(2.0f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex3d(x0, y + 0.001, z0);
        GL11.glVertex3d(x0, y + 0.001, z1);
        GL11.glVertex3d(x1, y + 0.001, z1);
        GL11.glVertex3d(x1, y + 0.001, z0);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthFunc(prevDepthFunc);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();

        String label = (state.progress + 1) + "/" + state.sequence.size();
        double cx = state.originX + gx + 0.5 - camX;
        double cz = state.originZ + gz + 0.5 - camZ;
        double cy = state.originY + 1.2 - camY;
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, cz);
        GL11.glRotatef(-p.rotationYaw, 0, 1, 0);
        GL11.glRotatef(p.rotationPitch, 1, 0, 0);
        float s = 0.04f;
        GL11.glScalef(-s, -s, s);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        int w = mc.fontRenderer.getStringWidth(label);
        mc.fontRenderer.drawString(label, -w / 2, 0, 0xFFFFFF00, true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}

package net.puket.lgsolver.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;

/**
 * Through-wall tracer/ESP for LootGames puzzle master blocks. Draws a coloured
 * wireframe box on each target, a tracer line from the camera to it, and a
 * distance label — so the player can spot a puzzle master from across the
 * render distance instead of hunting for the block animation with F3.
 *
 * <p>Targets are supplied by {@code ClientEvents} as {@code int[]{x,y,z,kind}}
 * where {@code kind} is {@link #KIND_MS} or {@link #KIND_GOL}. Rendering uses
 * the same interpolated-camera convention as {@link HintRenderer}: translate
 * by {@code -interpolatedPlayerPos} then draw in world coordinates.
 */
public final class EspRenderer {

    public static final int KIND_MS = 0;
    public static final int KIND_GOL = 1;
    /** Un-activated puzzle master (game not yet chosen). */
    public static final int KIND_PUZZLE = 2;

    private static final double BOX_INSET = 0.02;

    public void render(float partialTicks, List<int[]> targets) {
        if (targets == null || targets.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null) return;

        double camX = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
        double camY = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
        double camZ = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;
        double eyeY = camY + p.getEyeHeight();

        // Fog is per-dimension and per-setting — restore what was there.
        boolean fogWasEnabled = GL11.glGetBoolean(GL11.GL_FOG);

        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        for (int[] t : targets) {
            float r, g, b;
            if (t[3] == KIND_GOL)         { r = 0.9f; g = 0.3f; b = 1.0f; }
            else if (t[3] == KIND_PUZZLE) { r = 1.0f; g = 0.85f; b = 0.1f; }
            else                          { r = 0.2f; g = 0.9f; b = 1.0f; }

            double x0 = t[0] - BOX_INSET, y0 = t[1] - BOX_INSET, z0 = t[2] - BOX_INSET;
            double x1 = t[0] + 1 + BOX_INSET, y1 = t[1] + 1 + BOX_INSET, z1 = t[2] + 1 + BOX_INSET;

            GL11.glColor4f(r, g, b, 0.9f);
            GL11.glLineWidth(2.0f);
            drawBox(x0, y0, z0, x1, y1, z1);

            // Tracer from eye to block centre.
            double cx = t[0] + 0.5, cy = t[1] + 0.5, cz = t[2] + 0.5;
            GL11.glColor4f(r, g, b, 0.6f);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex3d(camX, eyeY, camZ);
            GL11.glVertex3d(cx, cy, cz);
            GL11.glEnd();
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();

        // Distance labels (billboarded).
        for (int[] t : targets) {
            double dx = (t[0] + 0.5) - camX;
            double dy = (t[1] + 0.5) - eyeY;
            double dz = (t[2] + 0.5) - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String kind = t[3] == KIND_GOL ? "GOL " : t[3] == KIND_PUZZLE ? "Puzzle " : "MS ";
            String label = kind + String.format(Locale.ROOT, "%.0fm", dist);

            GL11.glPushMatrix();
            GL11.glTranslated((t[0] + 0.5) - camX, (t[1] + 1.4) - camY, (t[2] + 0.5) - camZ);
            GL11.glRotatef(-p.rotationYaw, 0, 1, 0);
            GL11.glRotatef(p.rotationPitch, 1, 0, 0);
            float s = 0.03f;
            GL11.glScalef(-s, -s, s);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            int w = mc.fontRenderer.getStringWidth(label);
            mc.fontRenderer.drawString(label, -w / 2, 0, 0xFFFFFFFF, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glPopMatrix();
        }

        // Hand the pipeline back exactly as we found it.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        if (fogWasEnabled) GL11.glEnable(GL11.GL_FOG);
    }

    private static void drawBox(double x0, double y0, double z0,
                                double x1, double y1, double z1) {
        GL11.glBegin(GL11.GL_LINES);
        // bottom
        edge(x0, y0, z0, x1, y0, z0); edge(x1, y0, z0, x1, y0, z1);
        edge(x1, y0, z1, x0, y0, z1); edge(x0, y0, z1, x0, y0, z0);
        // top
        edge(x0, y1, z0, x1, y1, z0); edge(x1, y1, z0, x1, y1, z1);
        edge(x1, y1, z1, x0, y1, z1); edge(x0, y1, z1, x0, y1, z0);
        // verticals
        edge(x0, y0, z0, x0, y1, z0); edge(x1, y0, z0, x1, y1, z0);
        edge(x1, y0, z1, x1, y1, z1); edge(x0, y0, z1, x0, y1, z1);
        GL11.glEnd();
    }

    private static void edge(double ax, double ay, double az,
                             double bx, double by, double bz) {
        GL11.glVertex3d(ax, ay, az);
        GL11.glVertex3d(bx, by, bz);
    }
}

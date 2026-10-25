package com.volcanicmg.mapsketch.client;

/**
 * One frame of Xaero's world map camera, and GUI-pixel / world-block conversions. Xaero maps a raw window pixel
 * to {@code (raw - floor(winW / 2)) / scale + cameraX}.
 *
 * @param scale    raw window pixels per block
 * @param guiScale raw pixels per GUI pixel
 */
public record MapView(double camX, double camZ, double scale, String dim, double guiScale, int winW, int winH) {
    public double worldX(double guiX) {
        return (guiX * guiScale - winW / 2) / scale + camX;
    }

    public double worldZ(double guiY) {
        return (guiY * guiScale - winH / 2) / scale + camZ;
    }

    public double guiX(double worldX) {
        return ((worldX - camX) * scale + winW / 2) / guiScale;
    }

    public double guiY(double worldZ) {
        return ((worldZ - camZ) * scale + winH / 2) / guiScale;
    }

    /** @return world blocks covered by {@code guiPx} GUI pixels */
    public double blocks(double guiPx) {
        return guiPx * guiScale / scale;
    }

    /** @return visible world box {minX, minZ, maxX, maxZ} */
    public double[] box() {
        return new double[]{worldX(0), worldZ(0), worldX(winW / guiScale), worldZ(winH / guiScale)};
    }
}

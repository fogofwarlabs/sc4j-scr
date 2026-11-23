package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

/**
 * An image's iscript created a sprite ({@code sprol}, {@code sprul}, {@code lowsprul}, {@code highsprol}, {@code sproluselo} and the other sprite
 * instructions), after the created sprite's Init ran. The sprites are named by their slots in the sprite pool, as they were when the event was
 * reported.
 */
public final class SpriteCreatedEvent extends SimulationEvent {
    private final int sprite;
    private final int spriteType;
    private final int sourceSprite;
    private final int sourceImageType;

    /**
     * Creates a {@link SpriteCreatedEvent} instance.
     *
     * @param sprite          the sprite pool slot of the created sprite
     * @param spriteType      the {@code sprites.dat} entry of the created sprite
     * @param sourceSprite    the sprite pool slot of the sprite whose image ran the instruction
     * @param sourceImageType the {@code images.dat} entry of the image that ran the instruction
     */
    public SpriteCreatedEvent(int sprite, int spriteType, int sourceSprite, int sourceImageType) {
        this.sprite          = sprite;
        this.spriteType      = spriteType;
        this.sourceSprite    = sourceSprite;
        this.sourceImageType = sourceImageType;
    }

    /**
     * @return the sprite pool slot of the created sprite
     */
    public int getSprite() {
        return this.sprite;
    }

    /**
     * @return the {@code sprites.dat} entry of the created sprite
     */
    public int getSpriteType() {
        return this.spriteType;
    }

    /**
     * @return the sprite pool slot of the sprite whose image ran the instruction
     */
    public int getSourceSprite() {
        return this.sourceSprite;
    }

    /**
     * @return the {@code images.dat} entry of the image that ran the instruction
     */
    public int getSourceImageType() {
        return this.sourceImageType;
    }
}

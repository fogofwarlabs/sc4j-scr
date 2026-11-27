package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsGrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws animated water or heat distortion over masked HD terrain tiles.
 */
final class HdTerrainEffect implements AutoCloseable {
    /**
     * The values of one masked tile of {@link #draw}: left and top in framebuffer pixels, then the tile size.
     */
    static final int TILE_VALUES = 3;
    static final int TILE_LEFT = 0;
    static final int TILE_TOP = 1;
    static final int TILE_EXTENT = 2;

    /**
     * The water map-coordinate scale.
     */
    private static final float WATER_SCALE = 5;
    /**
     * The heat-distortion map-coordinate scale.
     */
    private static final float HEAT_SCALE = 2;
    /**
     * The largest time step in milliseconds added per update.
     */
    private static final int MAXIMUM_TIME_STEP = 16;
    /**
     * The foliage frames of one effect frame.
     */
    private static final long FOLIAGE_FRAMES_PER_EFFECT_FRAME = 2L;
    /**
     * The width and height of clip space.
     */
    private static final float CLIP_SPACE_SIZE = 2.0f;

    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "Terrain effect";
    private static final float MILLISECONDS_PER_SECOND = 1000.0f;
    /**
     * The texture units of the samplers: the source, then the normal and detail arrays or the noise texture.
     */
    private static final int SOURCE_UNIT = 0;
    private static final int NORMAL_UNIT = 1;
    private static final int DETAIL_UNIT = 2;
    private static final int NOISE_UNIT = 1;
    /**
     * The vertex attributes: position, texture coordinates and the two map coordinates, two floats each.
     */
    private static final int VERTEX_ATTRIBUTES = 4;
    private static final int VECTOR_COMPONENTS = 2;
    private static final int VERTEX_FLOATS = VERTEX_ATTRIBUTES * VECTOR_COMPONENTS;

    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         layout(location = 2) in vec2 map1;
                                                         layout(location = 3) in vec2 map2;
                                                         out vec2 texCoord;
                                                         out vec2 mapCoord;
                                                         out vec2 mapCoord2;
                                                         void main() {
                                                             gl_Position = vec4(position, 0.0, 1.0);
                                                             texCoord = coordinates;
                                                             mapCoord = map1;
                                                             mapCoord2 = map2;
                                                         }
                                                         """;
    private static final @NotNull String WATER_SHADER = """
                                                        #version 330 core
                                                        in vec2 texCoord;
                                                        in vec2 mapCoord;
                                                        in vec2 mapCoord2;
                                                        layout(location = 0) out vec4 frag_color;
                                                        // water.glsl DEFERRED (lit pass 0x21): specular and normal targets (attachments 3 and 4 of the lit scene).
                                                        layout(location = 3) out vec4 frag_specular;
                                                        layout(location = 4) out vec4 frag_normal;
                                                        uniform sampler2D spriteTex;
                                                        uniform sampler2DArray normalTex;
                                                        uniform sampler2DArray detailTex;
                                                        uniform vec4 data;
                                                        uniform vec4 frames;
                                                        vec3 scale(vec3 v) { return v * 2.0 - 1.0; }
                                                        vec2 scale(vec2 v) { return v * 2.0 - 1.0; }
                                                        vec3 WaterSample(float time, float animTime) {
                                                            float t = time / 20.0;
                                                            vec2 mappedUV1 = mapCoord;
                                                            mappedUV1.x -= (t / 2.0);
                                                            mappedUV1.y -= (t / 1.0);
                                                            vec2 mappedUV2 = mapCoord2;
                                                            mappedUV2.x -= t * 2.0;
                                                            mappedUV2.y -= t * 1.8;
                                                            vec3 normalFrameOne = texture(normalTex, vec3(mappedUV1, frames.x)).rgb;
                                                            vec3 normalFrameTwo = texture(normalTex, vec3(mappedUV1, frames.y)).rgb;
                                                            vec3 normal = mix(normalFrameOne, normalFrameTwo, animTime);
                                                            vec3 normal2FrameOne = texture(detailTex, vec3(mappedUV2, frames.z)).rgb;
                                                            vec3 normal2FrameTwo = texture(detailTex, vec3(mappedUV2, frames.w)).rgb;
                                                            vec3 normalDetail = mix(normal2FrameOne, normal2FrameTwo, animTime);
                                                            float detailScale = 0.6;
                                                            normal = scale(normal);
                                                            normalDetail = scale(normalDetail) * detailScale;
                                                            return normalize(vec3(vec2(normal.xy + normalDetail.xy) * detailScale, normal.z));
                                                        }
                                                        float WaterSpecPower(vec3 normal, vec2 uv, float gameX) {
                                                            vec2 specUV = scale(uv);
                                                            float lightX = mix(-0.1, 0.1, 1.0 - clamp(gameX, 0.0, 1.0));
                                                            float xDistance = clamp(1.0 - distance(specUV.x, lightX), 0, 1);
                                                            float yDistance = clamp(1.0 - distance(specUV.y, -0.2), 0, 1);
                                                            return yDistance * pow(xDistance, 3) * 0.6;
                                                        }
                                                        void main() {
                                                            float gameX = data.x;
                                                            float textureFade = data.y;
                                                            float time = data.z;
                                                            vec4 tex = texture(spriteTex, texCoord);
                                                            vec3 groundColor = tex.rgb;
                                                            vec3 normal = WaterSample(time, textureFade);
                                                            vec2 uv = texCoord;
                                                            uv.x += normal.x * 0.019;
                                                            uv.y += normal.y * 0.029;
                                                            vec4 texColor = texture(spriteTex, uv);
                                                            float destMask = min(tex.a, texColor.a);
                                                            float sourceMask = tex.a;
                                                            vec3 result = mix(texColor.rgb, groundColor, 1.0 - destMask);
                                                            float spower = WaterSpecPower(normal, texCoord, gameX);
                                                            float specValue = ((normal.x * 1.2 + normal.y + normal.z) / 3.0) * spower;
                                                            vec3 waterColor = result + specValue;
                                                            groundColor = mix(groundColor, groundColor + specValue, sourceMask);
                                                            frag_specular = vec4(vec3(0.0), 1.0);
                                                            frag_normal = vec4((normal + 1.0) / 2.0, destMask);
                                                            frag_color = vec4(mix(groundColor, waterColor, destMask), 1.0);
                                                        }
                                                        """;
    private static final @NotNull String HEAT_SHADER = """
                                                       #version 330 core
                                                       in vec2 texCoord;
                                                       in vec2 mapCoord;
                                                       layout(location = 0) out vec4 frag_color;
                                                       // heat_distortion.glsl DEFERRED (lit pass 0x23): emissive, specular and normal targets (attachments 2..4).
                                                       layout(location = 2) out vec4 frag_emissive;
                                                       layout(location = 3) out vec4 frag_specular;
                                                       layout(location = 4) out vec4 frag_normal;
                                                       uniform vec2 invResolution;
                                                       uniform vec4 data;
                                                       uniform sampler2D spriteTex;
                                                       uniform sampler2D sampleTex;
                                                       #define PI (3.14)
                                                       void main() {
                                                           float time = data.z;
                                                           vec2 noiseUV = vec2(mapCoord.x, mapCoord.y + (time / 10.0));
                                                           noiseUV.x = noiseUV.x + (cos(time / 10.0) * 0.3);
                                                           vec4 noise = texture(sampleTex, noiseUV);
                                                           vec4 baseColor = texture(spriteTex, texCoord);
                                                           vec2 sOffset;
                                                           sOffset.x = cos(noise.r * PI + time * 0.5) * 1.9;
                                                           sOffset.y = sin(noise.g * PI + time * 0.5) * 0.5;
                                                           sOffset *= invResolution;
                                                           float redBleed = 1.2;
                                                           vec2 uv = texCoord + sOffset;
                                                           vec4 offsetColor = texture(spriteTex, uv);
                                                           float intensity = min((offsetColor.r * redBleed) * 2.0, 1);
                                                           intensity *= mix(offsetColor.a, 0, step(offsetColor.r, 0.2));
                                                           frag_color.rgb = mix(baseColor.rgb, offsetColor.rgb, intensity);
                                                           frag_color.a = 1.0;
                                                           frag_emissive = vec4(max(offsetColor.r - 0.5, 0.0) * 4.0, 0.0, 0.0, 1.0);
                                                           frag_specular = frag_color;
                                                           // normal_default(): the flat normal.
                                                           frag_normal = vec4(0.5, 0.5, 0.0, 1.0);
                                                       }
                                                       """;

    /**
     * Creates an {@link HdTerrainEffect} instance for animated water.
     *
     * @param masks      the tileset water-mask frames
     * @param normals    the first normal set ({@code water_normal_2})
     * @param details    the detail normal set ({@code water_normal_1})
     * @param compressed whether S3TC textures can be uploaded compressed
     * @return the created {@link HdTerrainEffect} instance
     * @throws IOException if a frame is invalid
     */
    static @NotNull HdTerrainEffect createWater(@NotNull DdsGrpFile masks,
                                                @NotNull DdsGrpFile normals, @NotNull DdsGrpFile details,
                                                boolean compressed) throws IOException {
        return new HdTerrainEffect(masks, normals, details, null, compressed);
    }

    /**
     * Creates an {@link HdTerrainEffect} instance for heat distortion.
     *
     * @param masks      the tileset mask frames
     * @param noise      the {@code effect/noise.dds} texture
     * @param compressed whether S3TC textures can be uploaded compressed
     * @return the created {@link HdTerrainEffect} instance
     * @throws IOException if a frame is invalid
     */
    static @NotNull HdTerrainEffect createHeatDistortion(@NotNull DdsGrpFile masks, @NotNull DdsFile noise, boolean compressed) throws IOException {
        return new HdTerrainEffect(masks, null, null, noise, compressed);
    }

    private static int uploadArray(@NotNull DdsGrpFile frames, boolean compressed, int wrapMode) throws IOException {
        DdsFile firstFrame = frames.getFrame(0);
        int texture = glGenTextures();

        glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, wrapMode);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, 0);

        boolean compressedUpload = compressed && firstFrame.getFormat() == DdsFile.Format.DXT1;
        int frameCount = frames.getFrameCount();

        if (compressedUpload) {
            glCompressedTexImage3D(GL_TEXTURE_2D_ARRAY,
                                   0,
                                   GL_COMPRESSED_RGBA_S3TC_DXT1_EXT,
                                   firstFrame.getWidth(),
                                   firstFrame.getHeight(),
                                   frameCount,
                                   0,
                                   firstFrame.getLevelSize(0) * frameCount,
                                   0L);
        } else {
            glTexImage3D(GL_TEXTURE_2D_ARRAY,
                         0,
                         GL_RGBA8,
                         firstFrame.getWidth(),
                         firstFrame.getHeight(),
                         frameCount,
                         0,
                         GL_RGBA,
                         GL_UNSIGNED_BYTE,
                         (ByteBuffer) null);
        }

        // A frame that cannot be uploaded deletes the texture array.
        try {
            for (int frame = 0; frame < frameCount; frame++) {
                DdsFile frameTexture = frames.getFrame(frame);

                if (frameTexture.getWidth() != firstFrame.getWidth() || frameTexture.getHeight() != firstFrame.getHeight()) {
                    throw Exceptions.newIOException("DDS container frame {0} size differs from frame 0.", frame);
                }

                ByteBuffer upload;

                if (compressedUpload) {
                    upload = MemoryUtil.memAlloc(frameTexture.getLevelSize(0));
                    frameTexture.copyLevel(0, upload);
                } else {
                    byte[] pixels = frameTexture.decodeRgba();

                    upload = MemoryUtil.memAlloc(pixels.length);
                    upload.put(pixels);
                }

                try {
                    upload.flip();
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

                    if (compressedUpload) {
                        glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY,
                                                  0,
                                                  0,
                                                  0,
                                                  frame,
                                                  firstFrame.getWidth(),
                                                  firstFrame.getHeight(),
                                                  1,
                                                  GL_COMPRESSED_RGBA_S3TC_DXT1_EXT,
                                                  upload);
                    } else {
                        glTexSubImage3D(GL_TEXTURE_2D_ARRAY,
                                        0,
                                        0,
                                        0,
                                        frame,
                                        firstFrame.getWidth(),
                                        firstFrame.getHeight(),
                                        1,
                                        GL_RGBA,
                                        GL_UNSIGNED_BYTE,
                                        upload);
                    }
                } finally {
                    MemoryUtil.memFree(upload);
                }
            }
        } catch (IOException | RuntimeException exception) {
            glDeleteTextures(texture);

            throw exception;
        }

        return texture;
    }

    private static int uploadTexture(@NotNull DdsFile texture) {
        int name = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, name);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);

        byte[] pixels = texture.decodeRgba();
        ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

        try {
            upload.put(pixels).flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texture.getWidth(), texture.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);
        } finally {
            MemoryUtil.memFree(upload);
        }

        return name;
    }

    private final boolean heat;

    private final @NotNull ShaderProgram program;
    private final int dataUniform;
    private final int framesUniform;
    private final int inverseResolutionUniform;
    private final int vertexArray;
    private final int vertexBuffer;
    private @Nullable FloatBuffer vertices;

    private final int maskTexture;
    private final int normalTexture;
    private final int detailTexture;
    private final int noiseTexture;
    private final int normalFrames;
    private final int detailFrames;

    private int frame;
    private long frameSwitchMilliseconds;
    private long lastUpdateMilliseconds;
    private float fade;
    private float time;

    private int sourceTexture;
    private int sourceWidth;
    private int sourceHeight;

    private boolean closed;

    private HdTerrainEffect(@NotNull DdsGrpFile masks,
                            @Nullable DdsGrpFile normals, @Nullable DdsGrpFile details,
                            @Nullable DdsFile noise,
                            boolean compressed) throws IOException {
        this.heat = noise != null;

        // A failing step closes the effect, which releases what the steps before it created.
        try {
            this.program = GraphicsDevice.getCurrent().createShaderProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, this.heat ? HEAT_SHADER : WATER_SHADER);
            this.program.use();
            glUniform1i(this.program.getUniformLocation("spriteTex"), SOURCE_UNIT);
            glUniform1i(this.program.getUniformLocation("normalTex"), NORMAL_UNIT);
            glUniform1i(this.program.getUniformLocation("detailTex"), DETAIL_UNIT);
            glUniform1i(this.program.getUniformLocation("sampleTex"), NOISE_UNIT);
            this.dataUniform              = this.program.getUniformLocation("data");
            this.framesUniform            = this.program.getUniformLocation("frames");
            this.inverseResolutionUniform = this.program.getUniformLocation("invResolution");
            this.vertexArray              = glGenVertexArrays();
            this.vertexBuffer             = glGenBuffers();
            this.vertices                 = MemoryUtil.memAllocFloat(GraphicsDevice.QUAD_VERTICES * VERTEX_FLOATS);
            glBindVertexArray(this.vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, (long) this.vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);

            for (int attribute = 0; attribute < VERTEX_ATTRIBUTES; attribute++) {
                glEnableVertexAttribArray(attribute);
                glVertexAttribPointer(attribute,
                                      VECTOR_COMPONENTS,
                                      GL_FLOAT,
                                      false,
                                      VERTEX_FLOATS * Float.BYTES,
                                      (long) attribute * VECTOR_COMPONENTS * Float.BYTES);
            }

            this.maskTexture   = uploadArray(masks, compressed, GL_CLAMP_TO_EDGE);
            this.normalTexture = normals != null ? uploadArray(normals, compressed, GL_REPEAT) : 0;
            this.detailTexture = details != null ? uploadArray(details, compressed, GL_REPEAT) : 0;
            this.noiseTexture  = noise != null ? uploadTexture(noise) : 0;
            this.normalFrames  = normals != null ? normals.getFrameCount() : 1;
            this.detailFrames  = details != null ? details.getFrameCount() : 1;

            this.frame                   = 0;
            this.frameSwitchMilliseconds = 0L;
            this.lastUpdateMilliseconds  = 0L;
            this.fade                    = 0;
            this.time                    = 0;

            this.sourceTexture = 0;
            this.sourceWidth   = 0;
            this.sourceHeight  = 0;

            this.closed = false;
        } catch (IOException | RuntimeException exception) {
            this.close();

            throw exception;
        }
    }

    /**
     * @return the texture array of the tile-mask frames, indexed by mask frame
     */
    int getMaskTexture() {
        return this.maskTexture;
    }


    /**
     * Advances the effect frame, fade and elapsed time from the presentation clock.
     *
     * @param nowMilliseconds the presentation clock in milliseconds
     */
    void update(long nowMilliseconds) {
        if (this.lastUpdateMilliseconds == 0) {
            this.lastUpdateMilliseconds  = nowMilliseconds;
            this.frameSwitchMilliseconds = nowMilliseconds;
        }

        long period = FOLIAGE_FRAMES_PER_EFFECT_FRAME * HdFoliage.FRAME_MILLISECONDS;

        if (nowMilliseconds > this.frameSwitchMilliseconds + period) {
            this.frame++;
            this.frameSwitchMilliseconds = nowMilliseconds;
        }

        float fraction = (float) (nowMilliseconds - this.frameSwitchMilliseconds) / period;
        long step = Math.min(nowMilliseconds - this.lastUpdateMilliseconds, MAXIMUM_TIME_STEP);

        this.fade                   = Math.max(0, Math.min(1, fraction));
        this.lastUpdateMilliseconds = nowMilliseconds;
        this.time += step / MILLISECONDS_PER_SECOND;
    }

    /**
     * Draws the effect over the masked tiles into the bound scene framebuffer's color attachment {@code 0}.
     *
     * @param framebufferWidth  the framebuffer width
     * @param framebufferHeight the framebuffer height
     * @param viewWidth         the world viewport width
     * @param viewHeight        the world viewport height
     * @param cameraX           the world coordinate at the viewport's left edge
     * @param cameraY           the world coordinate at the viewport's top edge
     * @param scale             the screen pixels per world pixel
     * @param mapWidth          the map width in world pixels
     * @param sourceAttachment  the attachment holding the terrain color and mask (the effect target)
     * @param deferred          whether the lit G-buffer targets are written too
     * @param tiles             per masked tile: left and top in framebuffer pixels (top-down) and the tile size
     * @param tileCount         the number of masked tiles
     */
    void draw(int framebufferWidth, int framebufferHeight,
              int viewWidth, int viewHeight,
              float cameraX, float cameraY, float scale, float mapWidth,
              int sourceAttachment, boolean deferred,
              float @NotNull [] tiles, int tileCount) {
        if (this.sourceTexture == 0 || this.sourceWidth != framebufferWidth || this.sourceHeight != framebufferHeight) {
            if (this.sourceTexture == 0) {
                this.sourceTexture = glGenTextures();
            }

            glBindTexture(GL_TEXTURE_2D, this.sourceTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, framebufferWidth, framebufferHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

            this.sourceWidth  = framebufferWidth;
            this.sourceHeight = framebufferHeight;
        }

        glActiveTexture(GL_TEXTURE0 + SOURCE_UNIT);
        glBindTexture(GL_TEXTURE_2D, this.sourceTexture);
        glReadBuffer(sourceAttachment);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, framebufferHeight - viewHeight, 0, framebufferHeight - viewHeight, viewWidth, viewHeight);

        this.program.use();
        glUniform4f(this.dataUniform, mapWidth > 0 ? cameraX / mapWidth : 0, this.fade, this.time, 0);

        if (this.heat) {
            glUniform2f(this.inverseResolutionUniform, 1.0f / framebufferWidth, 1.0f / framebufferHeight);
            glActiveTexture(GL_TEXTURE0 + NOISE_UNIT);
            glBindTexture(GL_TEXTURE_2D, this.noiseTexture);
        } else {
            glUniform4f(this.framesUniform,
                        this.frame % this.normalFrames,
                        (this.frame + 1) % this.normalFrames,
                        this.frame % this.detailFrames,
                        (this.frame + 1) % this.detailFrames);
            glActiveTexture(GL_TEXTURE0 + NORMAL_UNIT);
            glBindTexture(GL_TEXTURE_2D_ARRAY, this.normalTexture);
            glActiveTexture(GL_TEXTURE0 + DETAIL_UNIT);
            glBindTexture(GL_TEXTURE_2D_ARRAY, this.detailTexture);
        }

        glActiveTexture(GL_TEXTURE0);

        float mapScale = TerrainTileset.TILE_SIZE * (this.heat ? HEAT_SCALE : WATER_SCALE);
        int vertexFloatCount = tileCount * GraphicsDevice.QUAD_VERTICES * VERTEX_FLOATS;

        if (this.vertices.capacity() < vertexFloatCount) {
            MemoryUtil.memFree(this.vertices);
            this.vertices = MemoryUtil.memAllocFloat(vertexFloatCount);

            glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, (long) vertexFloatCount * Float.BYTES, GL_STREAM_DRAW);
        }

        this.vertices.clear();

        for (int tile = 0; tile < tileCount; tile++) {
            float left = tiles[tile * TILE_VALUES + TILE_LEFT];
            float top = tiles[tile * TILE_VALUES + TILE_TOP];
            float size = tiles[tile * TILE_VALUES + TILE_EXTENT];

            float clipLeft = left * CLIP_SPACE_SIZE / framebufferWidth - 1;
            float clipRight = (left + size) * CLIP_SPACE_SIZE / framebufferWidth - 1;
            float clipTop = 1 - top * CLIP_SPACE_SIZE / framebufferHeight;
            float clipBottom = 1 - (top + size) * CLIP_SPACE_SIZE / framebufferHeight;

            float textureLeft = left / framebufferWidth;
            float textureRight = (left + size) / framebufferWidth;
            float textureTop = 1 - top / framebufferHeight;
            float textureBottom = 1 - (top + size) / framebufferHeight;

            float mapLeft = (cameraX + left / scale) / mapScale;
            float mapRight = (cameraX + (left + size) / scale) / mapScale;
            float mapTop = (cameraY + top / scale) / mapScale;
            float mapBottom = (cameraY + (top + size) / scale) / mapScale;

            this.putVertex(clipLeft, clipTop, textureLeft, textureTop, mapLeft, mapTop);
            this.putVertex(clipRight, clipTop, textureRight, textureTop, mapRight, mapTop);
            this.putVertex(clipRight, clipBottom, textureRight, textureBottom, mapRight, mapBottom);
            this.putVertex(clipLeft, clipTop, textureLeft, textureTop, mapLeft, mapTop);
            this.putVertex(clipRight, clipBottom, textureRight, textureBottom, mapRight, mapBottom);
            this.putVertex(clipLeft, clipBottom, textureLeft, textureBottom, mapLeft, mapBottom);
        }

        this.vertices.flip();
        glBindVertexArray(this.vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferSubData(GL_ARRAY_BUFFER, 0, this.vertices);

        if (deferred) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        glColorMaski(WorldRenderer.COLOR_TARGET, true, true, true, true);

        for (int attachment = WorldRenderer.EFFECT_TARGET; attachment < HdLighting.ATTACHMENT_COUNT; attachment++) {
            boolean written = deferred && (attachment == HdLighting.SPECULAR_ATTACHMENT || attachment == HdLighting.NORMAL_ATTACHMENT ||
                                           this.heat && attachment == HdLighting.EMISSIVE_ATTACHMENT);

            glColorMaski(attachment, written, written, written, written);
        }

        glDrawArrays(GL_TRIANGLES, 0, tileCount * GraphicsDevice.QUAD_VERTICES);

        for (int attachment = WorldRenderer.EFFECT_TARGET; attachment < HdLighting.ATTACHMENT_COUNT; attachment++) {
            glColorMaski(attachment, true, true, true, true);
        }
    }

    private void putVertex(float positionX, float positionY, float textureX, float textureY, float mapX, float mapY) {
        this.vertices.put(positionX).put(positionY).put(textureX).put(textureY).put(mapX).put(mapY).put(mapX).put(mapY);
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link Closeable#close close}
     *         method of {@link Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Releases the textures, buffers and program in the reverse order of their creation. A failing constructor closes the effect too; the
     *         objects of the steps after the failed one are not created then.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                () -> glDeleteTextures(this.sourceTexture),
                () -> glDeleteTextures(this.noiseTexture),
                () -> glDeleteTextures(this.detailTexture),
                () -> glDeleteTextures(this.normalTexture),
                () -> glDeleteTextures(this.maskTexture),
                () -> MemoryUtil.memFree(this.vertices),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> this.program.close()
        );
    }
}

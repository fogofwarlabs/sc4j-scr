package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Resolves HD deferred color, effect and lighting targets into the world frame, then applies bloom. Screen-tile quads reference contiguous ranges of
 * the point lights that reach each tile.
 */
final class HdLighting implements AutoCloseable {
    /**
     * The screen tile size of the resolve quads, in framebuffer pixels.
     */
    static final int TILE_SIZE = 64;
    /**
     * The scene framebuffer's G-buffer attachments after its color and effect targets ({@link WorldRenderer#COLOR_TARGET},
     * {@link WorldRenderer#EFFECT_TARGET}): emissive, specular, normal and AO/depth/lighting.
     */
    static final int EMISSIVE_ATTACHMENT = 2;
    static final int SPECULAR_ATTACHMENT = 3;
    static final int NORMAL_ATTACHMENT = 4;
    static final int AO_DEPTH_ATTACHMENT = 5;
    /**
     * The number of scene framebuffer attachments: color, effect and the four G-buffer targets.
     */
    static final int ATTACHMENT_COUNT = AO_DEPTH_ATTACHMENT + 1;

    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "HD lighting";
    /**
     * The G-buffer targets that only the deferred path has, from {@link #EMISSIVE_ATTACHMENT} on.
     */
    private static final int GBUFFER_TARGETS = ATTACHMENT_COUNT - EMISSIVE_ATTACHMENT;
    /**
     * The positions of the G-buffer targets in {@link #gbufferTextures}.
     */
    private static final int GBUFFER_EMISSIVE = 0;
    private static final int GBUFFER_SPECULAR = SPECULAR_ATTACHMENT - EMISSIVE_ATTACHMENT;
    private static final int GBUFFER_NORMAL = NORMAL_ATTACHMENT - EMISSIVE_ATTACHMENT;
    private static final int GBUFFER_AO_DEPTH = AO_DEPTH_ATTACHMENT - EMISSIVE_ATTACHMENT;
    /**
     * The draw buffers of the resolve pass (the lit and bright targets) and of the blur passes.
     */
    private static final int RESOLVE_TARGET_COUNT = 2;
    private static final int BLUR_TARGET_COUNT = 1;
    /**
     * The floats of one light: position x, y, z and radius; color r, g, b and intensity (two RGBA32F buffer texels).
     */
    private static final int LIGHT_FLOATS = 8;
    private static final int LIGHT_X = 0;
    private static final int LIGHT_Y = 1;
    private static final int LIGHT_Z = 2;
    private static final int LIGHT_RADIUS = 3;
    private static final int LIGHT_RED = 4;
    private static final int LIGHT_GREEN = 5;
    private static final int LIGHT_BLUE = 6;
    private static final int LIGHT_INTENSITY = 7;
    /**
     * The initial light capacity of a frame; the light array doubles when it is full.
     */
    private static final int INITIAL_LIGHT_CAPACITY = 64;
    /**
     * The factor by which the light array and the light lists of the tiles grow.
     */
    private static final int CAPACITY_GROWTH = 2;
    /**
     * The floats of one RGBA32F texel of the light buffer.
     */
    private static final int TEXEL_FLOATS = 4;
    /**
     * The texture unit of {@code lightBuffer}.
     */
    private static final int LIGHT_BUFFER_UNIT = 6;
    /**
     * The mask of an unsigned color channel byte.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The components of a vertex attribute vector (position, texture coordinates, light range).
     */
    private static final int VECTOR_COMPONENTS = 2;
    /**
     * The full-screen quad's vertex attributes: position and texture coordinates.
     */
    private static final int POSITION_ATTRIBUTE = 0;
    private static final int COORDINATES_ATTRIBUTE = 1;
    private static final int QUAD_ATTRIBUTES = 2;
    private static final int QUAD_VERTEX_FLOATS = VECTOR_COMPONENTS * QUAD_ATTRIBUTES;
    /**
     * The resolve tile vertex attributes: position, texture coordinates and light range (offset and count).
     */
    private static final int TILE_ATTRIBUTES = 3;
    /**
     * The floats of one resolve vertex: position, texture coordinates, light offset and light count.
     */
    private static final int TILE_VERTEX_FLOATS = TILE_ATTRIBUTES * VECTOR_COMPONENTS;
    /**
     * The light tile passes: counting the lights of each tile, then filling the tile lists.
     */
    private static final int LIGHT_TILE_PASSES = 2;
    /**
     * The full-screen quad: two triangles of position and texture coordinates.
     */
    private static final float @NotNull [] QUAD = {
            0, 0, 0, 0,
            1, 0, 1, 0,
            1, 1, 1, 1,
            0, 0, 0, 0,
            1, 1, 1, 1,
            0, 1, 0, 1
    };

    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         out vec2 texCoord;
                                                         void main() {
                                                             gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                                                             texCoord = coordinates;
                                                         }
                                                         """;
    private static final @NotNull String BLIT_VERTEX_SHADER = """
                                                              #version 330 core
                                                              layout(location = 0) in vec2 position;
                                                              layout(location = 1) in vec2 coordinates;
                                                              layout(location = 2) in vec2 lightRange;
                                                              out vec2 texCoord;
                                                              flat out ivec2 lightCoord;
                                                              void main() {
                                                                  gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                                                                  texCoord = coordinates;
                                                                  lightCoord = ivec2(lightRange);
                                                              }
                                                              """;
    private static final @NotNull String BLIT_SHADER = """
                                                       #version 330 core
                                                       in vec2 texCoord;
                                                       flat in ivec2 lightCoord; // x == offset, y == count
                                                       layout(location = 0) out vec4 frag_color;
                                                       layout(location = 1) out vec4 frag_effect;
                                                       uniform sampler2D spriteTex;
                                                       uniform sampler2D effectTex;
                                                       uniform sampler2D emissiveTex;
                                                       uniform sampler2D specularTex;
                                                       uniform sampler2D normalTex;
                                                       uniform sampler2D ao_d_lTex;
                                                       uniform float spriteWidth;
                                                       uniform vec2 invResolution;
                                                       uniform float lightingScale;
                                                       uniform samplerBuffer lightBuffer;
                                                       uniform int debugView;
                                                       struct LightInfo { vec3 color; vec3 pos; };
                                                       struct FragmentInfo { vec3 pos; vec3 normal; vec4 specular; vec4 color; float ao; };
                                                       vec3 Lighting(LightInfo light, FragmentInfo fragment, float falloff, vec3 eye) {
                                                           vec3 lightDirection = normalize(light.pos - fragment.pos);
                                                           float lambertian = clamp(dot(lightDirection, fragment.normal), 0, 1);
                                                           float specValue = 0;
                                                           if (lambertian > 0) {
                                                               vec3 viewDir = normalize(eye - fragment.pos);
                                                               vec3 halfDir = normalize(lightDirection + viewDir);
                                                               float specAngle = clamp(dot(halfDir, fragment.normal), 0.0, 1.0);
                                                               specValue = pow(specAngle, 16.0);
                                                           }
                                                           vec3 result = lambertian * fragment.color.rgb * light.color;
                                                           result += specValue * fragment.specular.rgb;
                                                           result *= fragment.ao;
                                                           result *= falloff;
                                                           return result;
                                                       }
                                                       vec3 normal_final(vec3 v) {
                                                           vec3 normal = vec3(v.x, v.y, 0) * 2.0 - 1.0;
                                                           normal.z = max(1 - normal.x * normal.x - normal.y * normal.y, 0.1);
                                                           normal.z = sqrt(normal.z);
                                                           return normalize(normal);
                                                       }
                                                       void main() {
                                                           FragmentInfo fragment;
                                                           fragment.specular = texture(specularTex, texCoord);
                                                           fragment.normal = texture(normalTex, texCoord).rgb;
                                                           vec3 ao_d_l = texture(ao_d_lTex, texCoord).rgb;
                                                           vec3 emissive = texture(emissiveTex, texCoord).rgb;
                                                           vec4 effect = texture(effectTex, texCoord);
                                                           vec2 tc = vec2((effect.b + 0.0625) * 8 * spriteWidth, texCoord.y);
                                                           tc.x += texCoord.x;
                                                           vec4 adjustedEffect = texture(effectTex, tc);
                                                           float warpOffset = effect.r * spriteWidth * 8;
                                                           if (effect.b * adjustedEffect.b > 0) {
                                                               tc.x += warpOffset;
                                                               fragment.color = texture(spriteTex, tc);
                                                           } else {
                                                               fragment.color = texture(spriteTex, vec2(texCoord.x + warpOffset, texCoord.y));
                                                           }
                                                           fragment.normal = normal_final(fragment.normal);
                                                           float z = ao_d_l.g * (75 * lightingScale);
                                                           fragment.ao = ao_d_l.r;
                                                           vec2 resolution = 1 / invResolution;
                                                           vec3 eye = vec3(resolution * vec2(0.5, 0.25), 500 * lightingScale);
                                                           fragment.pos = vec3(0);
                                                           vec3 base_color = fragment.color.rgb;
                                                           LightInfo directional;
                                                           directional.pos = vec3(200, 50, 500);
                                                           directional.color = vec3(1.0);
                                                           vec3 lit_color = Lighting(directional, fragment, 1.0, eye);
                                                           lit_color = lit_color * (1 - effect.g * 0.5);
                                                           fragment.pos = vec3(gl_FragCoord.xy, z);
                                                           for (int i = 0; i < lightCoord.y; ++i) {
                                                               vec4 lightPosition = texelFetch(lightBuffer, lightCoord.x + 2 * i);
                                                               vec4 lightColor = texelFetch(lightBuffer, lightCoord.x + 2 * i + 1);
                                                               LightInfo light;
                                                               light.pos = lightPosition.xyz;
                                                               light.color = lightColor.rgb * 2;
                                                               float lightRadius = lightPosition.w;
                                                               float lightIntensity = lightColor.a * 0.6;
                                                               light.pos.y = resolution.y - light.pos.y;
                                                               light.pos.z = 125 * lightingScale;
                                                               float fragmentDistance = distance(fragment.pos.xy, light.pos.xy);
                                                               float falloff = clamp(lightRadius - fragmentDistance, 0.0, lightRadius);
                                                               falloff /= lightRadius;
                                                               falloff *= 0.9;
                                                               float power = mix(0, lightIntensity, falloff);
                                                               lit_color += Lighting(light, fragment, power, eye);
                                                           }
                                                           lit_color += emissive.rgb;
                                                           frag_color = vec4(mix(base_color, lit_color, ao_d_l.b), 1.0);
                                                           frag_effect = vec4(emissive, 1.0);
                                                           float b = dot(lit_color, vec3(0.30196078, 0.59215686, 0.10980392));
                                                           if (b > 1.8)
                                                               frag_effect.rgb += lit_color;
                                                           if (debugView >= 2) {
                                                               // Debug views: the light a white surface receives, or one G-buffer input.
                                                               vec3 view = base_color;
                                                               if (debugView == 2) {
                                                                   FragmentInfo white = fragment;
                                                                   white.color = vec4(1.0);
                                                                   white.pos = vec3(0);
                                                                   view = Lighting(directional, white, 1.0, eye) * (1 - effect.g * 0.5);
                                                                   white.pos = vec3(gl_FragCoord.xy, z);
                                                                   for (int i = 0; i < lightCoord.y; ++i) {
                                                                       vec4 lightPosition = texelFetch(lightBuffer, lightCoord.x + 2 * i);
                                                                       vec4 lightColor = texelFetch(lightBuffer, lightCoord.x + 2 * i + 1);
                                                                       LightInfo light;
                                                                       light.pos = vec3(lightPosition.x, resolution.y - lightPosition.y, 125 * lightingScale);
                                                                       light.color = lightColor.rgb * 2;
                                                                       float radius = lightPosition.w;
                                                                       float falloff = clamp(radius - distance(white.pos.xy, light.pos.xy), 0.0, radius) / radius * 0.9;
                                                                       view += Lighting(light, white, mix(0, lightColor.a * 0.6, falloff), eye);
                                                                   }
                                                                   view = mix(vec3(1.0), view, ao_d_l.b);
                                                               } else if (debugView == 4) {
                                                                   view = fragment.normal * 0.5 + 0.5;
                                                               } else if (debugView == 5) {
                                                                   view = vec3(fragment.ao);
                                                               } else if (debugView == 6) {
                                                                   view = emissive;
                                                               } else if (debugView == 7) {
                                                                   view = vec3(ao_d_l.b);
                                                               }
                                                               frag_color = vec4(view, 1.0);
                                                               frag_effect = vec4(0.0);
                                                           } else if (debugView == 1) {
                                                               // Light overlay: each point light radius as a ring in its color, its center as a white dot.
                                                               vec2 position = gl_FragCoord.xy;
                                                               for (int i = 0; i < lightCoord.y; ++i) {
                                                                   vec4 lightPosition = texelFetch(lightBuffer, lightCoord.x + 2 * i);
                                                                   vec4 lightColor = texelFetch(lightBuffer, lightCoord.x + 2 * i + 1);
                                                                   vec2 center = vec2(lightPosition.x, resolution.y - lightPosition.y);
                                                                   float distanceToLight = distance(position, center);
                                                                   if (abs(distanceToLight - lightPosition.w) < 1.5)
                                                                       frag_color = vec4(lightColor.rgb, 1.0);
                                                                   if (distanceToLight < 3.0 + 3.0 * lightColor.a)
                                                                       frag_color = vec4(1.0);
                                                               }
                                                           }
                                                       }
                                                       """;
    private static final @NotNull String BLUR_SHADER = """
                                                       #version 330 core
                                                       layout(location = 0) out vec4 frag_color;
                                                       in vec2 texCoord;
                                                       uniform sampler2D spriteTex;
                                                       uniform vec2 invResolution;
                                                       vec3 BlurTap(vec2 offset) {
                                                           return texture(spriteTex, texCoord + offset).rgb + texture(spriteTex, texCoord - offset).rgb;
                                                       }
                                                       void main() {
                                                           vec3 result = texture(spriteTex, texCoord).rgb * 0.227027;
                                                           result += BlurTap(invResolution * 1.0) * 0.1945946;
                                                           result += BlurTap(invResolution * 2.0) * 0.1216216;
                                                           result += BlurTap(invResolution * 3.0) * 0.054054;
                                                           result += BlurTap(invResolution * 4.0) * 0.016216;
                                                           frag_color = vec4(result, 1.0);
                                                       }
                                                       """;
    private static final @NotNull String BLOOM_SHADER = """
                                                        #version 330 core
                                                        layout(location = 0) out vec4 frag_color;
                                                        layout(location = 1) out vec4 frag_effect;
                                                        in vec2 texCoord;
                                                        uniform sampler2D spriteTex;
                                                        uniform sampler2D sampleTex;
                                                        void main() {
                                                            vec3 sourceColor = texture(sampleTex, texCoord).rgb;
                                                            vec3 bloomColor = texture(spriteTex, texCoord).rgb;
                                                            vec3 toneMapped = vec3(1.0) - exp(-bloomColor * 1.1);
                                                            frag_color = vec4(sourceColor + toneMapped, 1.0);
                                                            frag_effect = vec4(0.0);
                                                        }
                                                        """;

    private static @NotNull ShaderProgram compileProgram(@NotNull String vertexSource, @NotNull String fragmentSource,
                                                         @NotNull String @NotNull ... samplers) {
        ShaderProgram program = GraphicsDevice.getCurrent().createShaderProgram(SHADER_PROGRAM_NAME, vertexSource, fragmentSource);

        program.use();

        for (int unit = 0; unit < samplers.length; unit++) {
            glUniform1i(program.getUniformLocation(samplers[unit]), unit);
        }

        return program;
    }

    private static int createTarget(int width, int height) {
        int texture = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        return texture;
    }

    private static void putTileVertex(@NotNull FloatBuffer vertices, float horizontal, float vertical, float offset, float count) {
        vertices.put(horizontal).put(vertical).put(horizontal).put(vertical).put(offset).put(count);
    }

    private final @NotNull ShaderProgram blitProgram;
    private final @NotNull ShaderProgram blurProgram;
    private final @NotNull ShaderProgram bloomProgram;

    private final int blitSpriteWidth;
    private final int blitInverseResolution;
    private final int blitLightingScale;
    private final int blitDebugView;
    private final int blurInverseResolution;

    private final int vertexArray;
    private final int vertexBuffer;

    private final int tileVertexArray;
    private final int tileVertexBuffer;

    private final int lightBuffer;
    private final int lightTexture;
    private float @NotNull [] lights;
    private int lightCount;
    /**
     * The lights of each light tile in tile order ({@link #uploadLightTiles}), reused across frames.
     */
    private int @NotNull [] tileLightIndices;

    private int debugView;

    private final int @NotNull [] gbufferTextures;
    private int workFramebuffer;
    private int litTexture;
    private int brightTexture;
    private int blurTexture;
    private int width;
    private int height;

    private boolean closed;

    /**
     * Creates an {@link HdLighting} instance on the current OpenGL 3.3 context.
     */
    HdLighting() {
        // The target table comes first: a failing step closes the lighting, which releases what the steps before it created and reads the table.
        this.gbufferTextures = new int[GBUFFER_TARGETS];

        try {
            this.blitProgram = compileProgram(BLIT_VERTEX_SHADER,
                                              BLIT_SHADER,
                                              "spriteTex", "effectTex", "emissiveTex", "specularTex", "normalTex", "ao_d_lTex");
            glUniform1i(this.blitProgram.getUniformLocation("lightBuffer"), LIGHT_BUFFER_UNIT);
            this.blurProgram  = compileProgram(VERTEX_SHADER, BLUR_SHADER, "spriteTex");
            this.bloomProgram = compileProgram(VERTEX_SHADER, BLOOM_SHADER, "spriteTex", "sampleTex");

            this.blitSpriteWidth       = this.blitProgram.getUniformLocation("spriteWidth");
            this.blitInverseResolution = this.blitProgram.getUniformLocation("invResolution");
            this.blitLightingScale     = this.blitProgram.getUniformLocation("lightingScale");
            this.blitDebugView         = this.blitProgram.getUniformLocation("debugView");
            this.blurInverseResolution = this.blurProgram.getUniformLocation("invResolution");

            this.vertexArray  = glGenVertexArrays();
            this.vertexBuffer = glGenBuffers();
            glBindVertexArray(this.vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, QUAD, GL_STATIC_DRAW);
            glEnableVertexAttribArray(POSITION_ATTRIBUTE);
            glEnableVertexAttribArray(COORDINATES_ATTRIBUTE);
            glVertexAttribPointer(POSITION_ATTRIBUTE, VECTOR_COMPONENTS, GL_FLOAT, false, QUAD_VERTEX_FLOATS * Float.BYTES, 0L);
            glVertexAttribPointer(COORDINATES_ATTRIBUTE,
                                  VECTOR_COMPONENTS,
                                  GL_FLOAT,
                                  false,
                                  QUAD_VERTEX_FLOATS * Float.BYTES,
                                  (long) VECTOR_COMPONENTS * Float.BYTES);

            this.tileVertexArray  = glGenVertexArrays();
            this.tileVertexBuffer = glGenBuffers();
            glBindVertexArray(this.tileVertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, this.tileVertexBuffer);

            for (int attribute = 0; attribute < TILE_ATTRIBUTES; attribute++) {
                glEnableVertexAttribArray(attribute);
                glVertexAttribPointer(attribute,
                                      VECTOR_COMPONENTS,
                                      GL_FLOAT,
                                      false,
                                      TILE_VERTEX_FLOATS * Float.BYTES,
                                      (long) attribute * VECTOR_COMPONENTS * Float.BYTES);
            }

            this.lightBuffer      = glGenBuffers();
            this.lightTexture     = glGenTextures();
            this.lights           = new float[INITIAL_LIGHT_CAPACITY * LIGHT_FLOATS];
            this.lightCount       = 0;
            this.tileLightIndices = new int[0];

            this.debugView = 0;

            this.workFramebuffer = 0;
            this.litTexture      = 0;
            this.brightTexture   = 0;
            this.blurTexture     = 0;
            this.width           = 0;
            this.height          = 0;

            this.closed = false;
        } catch (RuntimeException exception) {
            this.close();

            throw exception;
        }
    }

    /**
     * Sets a debug view of the resolve: {@code 0} normal, {@code 1} point-light overlay, {@code 2} light received by a white surface, {@code 3}
     * albedo, {@code 4} normals, {@code 5} ambient occlusion, {@code 6} emissive, {@code 7} lighting amount.
     *
     * @param view the debug view
     */
    void setDebugView(int view) {
        this.debugView = view;
    }


    /**
     * Attaches the four deferred-only G-buffer targets (emissive, specular, normal, AO/depth/lighting) as color attachments {@code 2..5} of the bound
     * scene framebuffer, recreating them when the size changes.
     *
     * @param width  the framebuffer width
     * @param height the framebuffer height
     */
    void attachTargets(int width, int height) {
        if (width != this.width || height != this.height || this.gbufferTextures[0] == 0) {
            this.deleteTargets();

            for (int target = 0; target < this.gbufferTextures.length; target++) {
                this.gbufferTextures[target] = createTarget(width, height);
            }

            this.litTexture    = createTarget(width, height);
            this.brightTexture = createTarget(width, height);
            this.blurTexture   = createTarget(width, height);
            this.width         = width;
            this.height        = height;
        }

        for (int target = 0; target < this.gbufferTextures.length; target++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER,
                                   GL_COLOR_ATTACHMENT0 + EMISSIVE_ATTACHMENT + target,
                                   GL_TEXTURE_2D,
                                   this.gbufferTextures[target],
                                   0);
        }

        GraphicsDevice.getCurrent().selectColorTargets(ATTACHMENT_COUNT);

        float[] zero = {0, 0, 0, 0};

        for (int target = EMISSIVE_ATTACHMENT; target < ATTACHMENT_COUNT; target++) {
            glClearBufferfv(GL_COLOR, target, zero);
        }

        this.lightCount = 0;
    }

    /**
     * Adds a point light of this frame, in screen pixels with a top-left origin.
     *
     * @param screenX   the light's horizontal screen position
     * @param screenY   the light's vertical screen position
     * @param radius    the radius in screen pixels
     * @param color     the packed {@code 0xRRGGBB} color
     * @param intensity the intensity from {@code 0} through {@code 1}
     */
    void addLight(float screenX, float screenY, float radius, int color, float intensity) {
        if (radius <= 0) {
            return;
        }

        if ((this.lightCount + 1) * LIGHT_FLOATS > this.lights.length) {
            this.lights = Arrays.copyOf(this.lights, this.lights.length * CAPACITY_GROWTH);
        }

        int offset = this.lightCount++ * LIGHT_FLOATS;

        this.lights[offset + LIGHT_X]         = screenX;
        this.lights[offset + LIGHT_Y]         = screenY;
        this.lights[offset + LIGHT_Z]         = 0;
        this.lights[offset + LIGHT_RADIUS]    = radius;
        this.lights[offset + LIGHT_RED]       = (color >>> OverlayPass.RGB_RED_SHIFT & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.lights[offset + LIGHT_GREEN]     = (color >>> OverlayPass.RGB_GREEN_SHIFT & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.lights[offset + LIGHT_BLUE]      = (color & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.lights[offset + LIGHT_INTENSITY] = intensity;
    }

    /**
     * Resolves the G-buffer and writes the lit, bloomed scene into a target framebuffer.
     *
     * @param colorTexture      the G-buffer color target
     * @param effectTexture     the G-buffer effect target
     * @param targetFramebuffer the framebuffer receiving the result at color attachments {@code 0} and {@code 1}
     * @param viewWidth         the world viewport width, used for the scissor rectangle
     * @param viewHeight        the world viewport height
     * @param lightingScale     the screen pixels per HD pixel
     */
    void resolve(int colorTexture, int effectTexture, int targetFramebuffer, int viewWidth, int viewHeight, float lightingScale) {
        if (this.workFramebuffer == 0) {
            this.workFramebuffer = glGenFramebuffers();
        }

        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glBindVertexArray(this.vertexArray);
        glViewport(0, 0, this.width, this.height);

        glBindFramebuffer(GL_FRAMEBUFFER, this.workFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.litTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, this.brightTexture, 0);
        GraphicsDevice.getCurrent().selectColorTargets(RESOLVE_TARGET_COUNT);
        this.blitProgram.use();
        glUniform1f(this.blitSpriteWidth, 1.0f / this.width);
        glUniform2f(this.blitInverseResolution, 1.0f / this.width, 1.0f / this.height);
        glUniform1f(this.blitLightingScale, lightingScale);
        glUniform1i(this.blitDebugView, this.debugView);

        int tileVertices = this.uploadLightTiles();

        glActiveTexture(GL_TEXTURE0 + LIGHT_BUFFER_UNIT);
        glBindTexture(GL_TEXTURE_BUFFER, this.lightTexture);

        int[] inputs = {colorTexture, effectTexture,
                        this.gbufferTextures[GBUFFER_EMISSIVE], this.gbufferTextures[GBUFFER_SPECULAR],
                        this.gbufferTextures[GBUFFER_NORMAL], this.gbufferTextures[GBUFFER_AO_DEPTH]};

        for (int unit = 0; unit < inputs.length; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, inputs[unit]);
        }

        glBindVertexArray(this.tileVertexArray);
        glDrawArrays(GL_TRIANGLES, 0, tileVertices);
        glBindVertexArray(this.vertexArray);
        glActiveTexture(GL_TEXTURE0 + LIGHT_BUFFER_UNIT);
        glBindTexture(GL_TEXTURE_BUFFER, 0);

        this.blurProgram.use();
        glActiveTexture(GL_TEXTURE0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.blurTexture, 0);
        GraphicsDevice.getCurrent().selectColorTargets(BLUR_TARGET_COUNT);
        glBindTexture(GL_TEXTURE_2D, this.brightTexture);
        glUniform2f(this.blurInverseResolution, 1.0f / this.width, 0);
        glDrawArrays(GL_TRIANGLES, 0, GraphicsDevice.QUAD_VERTICES);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.brightTexture, 0);
        glBindTexture(GL_TEXTURE_2D, this.blurTexture);
        glUniform2f(this.blurInverseResolution, 0, 1.0f / this.height);
        glDrawArrays(GL_TRIANGLES, 0, GraphicsDevice.QUAD_VERTICES);

        glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
        glColorMaski(WorldRenderer.INDEX_TARGET, false, false, false, false);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, this.height - viewHeight, viewWidth, viewHeight);
        this.bloomProgram.use();
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.litTexture);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, this.brightTexture);
        glDrawArrays(GL_TRIANGLES, 0, GraphicsDevice.QUAD_VERTICES);
        glColorMaski(WorldRenderer.INDEX_TARGET, true, true, true, true);
    }

    /**
     * Fills the light buffer and resolve quads with a contiguous light range for each framebuffer tile.
     *
     * @return the number of tile vertices
     */
    private int uploadLightTiles() {
        int columns = (this.width + TILE_SIZE - 1) / TILE_SIZE;
        int rows = (this.height + TILE_SIZE - 1) / TILE_SIZE;
        int tileCount = columns * rows;
        // Each light visits only the tiles of its bounding square; the exact test is the one each tile made for every light, and the per-tile lists
        // keep the lights in ascending order, so the buffer is the same.
        int[] counts = new int[tileCount + 1];

        for (int pass = 0; pass < LIGHT_TILE_PASSES; pass++) {
            int[] next = pass == 0 ? null : counts.clone();

            for (int light = 0; light < this.lightCount; light++) {
                float lightX = this.lights[light * LIGHT_FLOATS + LIGHT_X];
                float lightY = this.lights[light * LIGHT_FLOATS + LIGHT_Y];
                float radius = this.lights[light * LIGHT_FLOATS + LIGHT_RADIUS];
                // A tile beyond the square on each side as a margin; the exact test decides.
                int firstColumn = Math.max(0, (int) Math.floor((lightX - radius) / TILE_SIZE) - 1);
                int lastColumn = Math.min(columns - 1, (int) Math.floor((lightX + radius) / TILE_SIZE) + 1);
                // Tile rows count from the framebuffer bottom; light positions from its top.
                int firstRow = Math.max(0, (int) Math.floor((this.height - (lightY + radius)) / TILE_SIZE) - 1);
                int lastRow = Math.min(rows - 1, (int) Math.floor((this.height - (lightY - radius)) / TILE_SIZE) + 1);

                for (int row = firstRow; row <= lastRow; row++) {
                    float top = this.height - Math.min(this.height, (row + 1) * TILE_SIZE);
                    float bottom = this.height - row * TILE_SIZE;

                    for (int column = firstColumn; column <= lastColumn; column++) {
                        float left = column * TILE_SIZE;
                        float right = Math.min(this.width, left + TILE_SIZE);
                        float horizontalDistance = lightX - Math.max(left, Math.min(lightX, right));
                        float verticalDistance = lightY - Math.max(top, Math.min(lightY, bottom));

                        if (horizontalDistance * horizontalDistance + verticalDistance * verticalDistance >= radius * radius) {
                            continue;
                        }

                        int tile = row * columns + column;

                        if (pass == 0) {
                            counts[tile + 1]++;
                        } else {
                            this.tileLightIndices[next[tile]++] = light;
                        }
                    }
                }
            }

            if (pass == 0) {
                for (int tile = 0; tile < tileCount; tile++) {
                    counts[tile + 1] += counts[tile];
                }

                if (this.tileLightIndices.length < counts[tileCount]) {
                    this.tileLightIndices = new int[Math.max(counts[tileCount], this.tileLightIndices.length * CAPACITY_GROWTH)];
                }
            }
        }

        int texelFloats = counts[tileCount] * LIGHT_FLOATS;
        FloatBuffer vertices = MemoryUtil.memAllocFloat(tileCount * GraphicsDevice.QUAD_VERTICES * TILE_VERTEX_FLOATS);
        FloatBuffer texels = MemoryUtil.memAllocFloat(Math.max(TEXEL_FLOATS, texelFloats));

        try {
            for (int entry = 0; entry < counts[tileCount]; entry++) {
                texels.put(this.lights, this.tileLightIndices[entry] * LIGHT_FLOATS, LIGHT_FLOATS);
            }

            while (texels.position() < TEXEL_FLOATS) {
                texels.put(0);
            }

            texels.flip();
            glBindBuffer(GL_TEXTURE_BUFFER, this.lightBuffer);
            glBufferData(GL_TEXTURE_BUFFER, texels, GL_STREAM_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, this.lightTexture);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, this.lightBuffer);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);

            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int tile = row * columns + column;
                    // The range in texels (four floats each): the tile's first light and its light count.
                    float offset = counts[tile] * LIGHT_FLOATS / (float) TEXEL_FLOATS;
                    float count = counts[tile + 1] - counts[tile];
                    float left = (float) column * TILE_SIZE / this.width;
                    float right = Math.min(1.0f, (float) (column + 1) * TILE_SIZE / this.width);
                    float bottom = (float) row * TILE_SIZE / this.height;
                    float top = Math.min(1.0f, (float) (row + 1) * TILE_SIZE / this.height);

                    putTileVertex(vertices, left, bottom, offset, count);
                    putTileVertex(vertices, right, bottom, offset, count);
                    putTileVertex(vertices, right, top, offset, count);
                    putTileVertex(vertices, left, bottom, offset, count);
                    putTileVertex(vertices, right, top, offset, count);
                    putTileVertex(vertices, left, top, offset, count);
                }
            }

            vertices.flip();
            glBindBuffer(GL_ARRAY_BUFFER, this.tileVertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);

            return tileCount * GraphicsDevice.QUAD_VERTICES;
        } finally {
            IgnoreException.execute(
                    () -> MemoryUtil.memFree(texels),
                    () -> MemoryUtil.memFree(vertices)
            );
        }
    }

    private void deleteTargets() {
        IgnoreException.execute(
                () -> glDeleteTextures(this.blurTexture),
                () -> glDeleteTextures(this.brightTexture),
                () -> glDeleteTextures(this.litTexture)
        );

        this.blurTexture   = 0;
        this.brightTexture = 0;
        this.litTexture    = 0;

        for (int target = this.gbufferTextures.length - 1; target >= 0; target--) {
            int texture = this.gbufferTextures[target];

            IgnoreException.execute(
                    () -> glDeleteTextures(texture)
            );

            this.gbufferTextures[target] = 0;
        }
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases the targets, buffers and programs in the reverse order of their creation. A failing constructor closes the lighting too; the
     * objects of the steps after the failed one are not created then.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                () -> glDeleteFramebuffers(this.workFramebuffer),
                this::deleteTargets,
                () -> glDeleteTextures(this.lightTexture),
                () -> glDeleteBuffers(this.lightBuffer),
                () -> glDeleteBuffers(this.tileVertexBuffer),
                () -> glDeleteVertexArrays(this.tileVertexArray),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> this.bloomProgram.close(),
                () -> this.blurProgram.close(),
                () -> this.blitProgram.close()
        );
    }
}

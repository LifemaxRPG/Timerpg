// Headless Filament smoke test: one building's front and side sprites as two textured quads meeting at a corner,
// sampled NEAREST so the pixel art stays crisp, rendered at the phone's size and read back to a PNG.
//
//   java -Djava.library.path=<dir of libfilament-jni.so> -cp <filament-java.jar> QuadSmoke.java \
//        <pixelquad.filamat> <front.png> <side.png> <out.png>
//
// Colours: sRGB textures, linear tone mapping, exposure 1 and no dithering, so the sprite's pixels come back unchanged.

import com.google.android.filament.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public class QuadSmoke {
    static final int W = 2340, H = 1080;
    static final boolean EXACT = Boolean.getBoolean("exact") || "1".equals(System.getProperty("exact"));

    public static void main(String[] args) throws Exception {
        Filament.init();
        Engine engine = Engine.create(Engine.Backend.OPENGL);
        System.out.println("backend: " + engine.getBackend());
        SwapChain swapChain = engine.createSwapChain(W, H, SwapChainFlags.CONFIG_READABLE);
        Renderer renderer = engine.createRenderer();
        Scene scene = engine.createScene();
        View view = engine.createView();
        Camera camera = engine.createCamera(EntityManager.get().create());

        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, W, H));
        view.setDithering(View.Dithering.NONE);
        view.setAntiAliasing(View.AntiAliasing.NONE);
        view.setColorGrading(new ColorGrading.Builder().toneMapper(new LinearToneMapper()).build(engine));
        // -Dexact=1: no post-processing and plain RGBA8 textures, so texels reach the buffer bit for bit
        // (the colour grading LUT otherwise moves about 4% of pixels by 1-2 levels in 255)
        if (EXACT) view.setPostProcessingEnabled(false);
        Renderer.ClearOptions clear = new Renderer.ClearOptions();
        if (EXACT) clear.setClearColor(0.68f, 0.81f, 0.93f, 1f);   // written as is
        else clear.setClearColor(0.42f, 0.62f, 0.85f, 1f);       // linear, sRGB-encoded on the way out
        clear.clear = true;
        renderer.setClearOptions(clear);

        byte[] matBytes = Files.readAllBytes(new File(args[0]).toPath());
        ByteBuffer matBuf = ByteBuffer.allocateDirect(matBytes.length).put(matBytes).flip();
        Material material = new Material.Builder().payload(matBuf, matBytes.length).build(engine);

        // A building 224 units wide and tall (one unit per sprite pixel); the side wall runs back 120 units.
        java.util.List<Object> owned = new java.util.ArrayList<>();
        Texture front = loadTexture(engine, args[1]);
        Texture side = loadTexture(engine, args[2]);
        TextureSampler nearest = new TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST,
                TextureSampler.WrapMode.CLAMP_TO_EDGE);
        float fw = 224, fh = 224, sd = 120;
        addQuad(engine, scene, owned, material, front, nearest, new float[]{
                0, 0, 0,   fw, 0, 0,   fw, fh, 0,   0, fh, 0});
        addQuad(engine, scene, owned, material, side, nearest, new float[]{
                fw, 0, 0,   fw, 0, -sd,   fw, fh, -sd,   fw, fh, 0});

        camera.setProjection(30.0, (double) W / H, 10.0, 5000.0, Camera.Fov.VERTICAL);
        camera.lookAt(420, 150, 520,   130, 105, -40);   // from the right, so the side wall shows
        camera.setExposure(1f);

        ByteBuffer pixels = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        AtomicBoolean done = new AtomicBoolean(false);
        long t0 = System.nanoTime();
        // A few frames: the first ones may be skipped while the backend warms up.
        for (int frame = 0; frame < 3 && !done.get(); frame++) {
            if (renderer.beginFrame(swapChain, 0L)) {
                renderer.render(view);
                if (frame == 2) {
                    renderer.readPixels(0, 0, W, H, new PixelBufferDescriptor(pixels, Texture.Format.RGBA,
                            Texture.Type.UBYTE, 1, 0, 0, W, (java.util.concurrent.Executor) Runnable::run,
                            () -> done.set(true)));
                }
                renderer.endFrame();
            }
            engine.flushAndWait();
        }
        for (int i = 0; i < 100 && !done.get(); i++) { engine.flushAndWait(); Thread.sleep(10); }
        System.out.printf("3 frames + readback: %.0f ms, read back: %s%n", (System.nanoTime() - t0) / 1e6, done.get());

        // readPixels gives rows bottom-up
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = ((H - 1 - y) * W + x) * 4;
            int r = pixels.get(i) & 255, g = pixels.get(i + 1) & 255, b = pixels.get(i + 2) & 255;
            out.setRGB(x, y, 0xff000000 | (r << 16) | (g << 8) | b);
        }
        ImageIO.write(out, "png", new File(args[3]));
        System.out.println("wrote " + args[3]);

        // Filament insists on teardown in order: renderables and instances before their material and textures.
        for (Object o : owned) {
            if (o instanceof Integer e) { engine.destroyEntity(e); EntityManager.get().destroy(e); }
            else if (o instanceof MaterialInstance mi) engine.destroyMaterialInstance(mi);
            else if (o instanceof VertexBuffer vb) engine.destroyVertexBuffer(vb);
            else if (o instanceof IndexBuffer ib) engine.destroyIndexBuffer(ib);
        }
        engine.destroyMaterial(material);
        engine.destroyTexture(front);
        engine.destroyTexture(side);
        engine.destroyCameraComponent(camera.getEntity());
        engine.destroyRenderer(renderer);
        engine.destroyView(view);
        engine.destroyScene(scene);
        engine.destroySwapChain(swapChain);
        engine.destroy();
    }

    static Texture loadTexture(Engine engine, String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        // Filament's UV origin is bottom-left; upload rows bottom-up so v=1 is the sprite's top.
        for (int y = h - 1; y >= 0; y--) for (int x = 0; x < w; x++) {
            int argb = img.getRGB(x, y);
            buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
        }
        buf.flip();
        Texture t = new Texture.Builder().width(w).height(h).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D).format(EXACT ? Texture.InternalFormat.RGBA8 : Texture.InternalFormat.SRGB8_A8).build(engine);
        t.setImage(engine, 0, new Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE));
        return t;
    }

    // Four corners (bottom-left, bottom-right, top-right, top-left) of a textured quad.
    static void addQuad(Engine engine, Scene scene, java.util.List<Object> owned, Material material, Texture tex, TextureSampler sampler, float[] p) {
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        ByteBuffer vb = ByteBuffer.allocateDirect(4 * 5 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) vb.putFloat(p[i * 3]).putFloat(p[i * 3 + 1]).putFloat(p[i * 3 + 2])
                .putFloat(uv[i * 2]).putFloat(uv[i * 2 + 1]);
        vb.flip();
        VertexBuffer vertices = new VertexBuffer.Builder().bufferCount(1).vertexCount(4)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 20)
                .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
                .build(engine);
        vertices.setBufferAt(engine, 0, vb);
        ByteBuffer ib = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder());
        for (short s : new short[]{0, 1, 2, 0, 2, 3}) ib.putShort(s);
        ib.flip();
        IndexBuffer indices = new IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);
        indices.setBuffer(engine, ib);

        MaterialInstance mi = material.createInstance();
        mi.setParameter("albedo", tex, sampler);
        int entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(-1000, -1000, -1000, 1000, 1000, 1000))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
                .material(0, mi)
                .culling(false)
                .build(engine, entity);
        scene.addEntity(entity);
        owned.add(entity); owned.add(mi); owned.add(vertices); owned.add(indices);
    }
}

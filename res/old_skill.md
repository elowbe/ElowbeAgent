---
name: java-desktop-gui
description: >
  Use this skill whenever the user wants to build, configure, or improve a Java desktop
  application using Maven, JavaFX, LWJGL, or Swing. Triggers include any mention of JavaFX
  scenes, FXML, LWJGL rendering, Swing components, Maven POM configuration, native libraries,
  platform-specific classifiers, OpenGL in Java, Java GUI, desktop app in Java, or packaging
  a Java app for distribution. Also use when the user wants to make a Java UI look polished,
  add animations, style components with CSS (JavaFX), use shaders (LWJGL), or modernize a
  Swing application. Always use this skill even if the user only asks about a single aspect
  like "how do I add LWJGL to my pom.xml" or "how do I style my JavaFX app".
---

# Java Desktop GUI Skill

This skill guides the creation of beautiful, production-grade Java desktop applications using Maven as the build system, with JavaFX, LWJGL, or Swing as the UI/rendering layer. It covers POM configuration, native library handling, plugin setup, and design philosophy for desktop UIs.

---

## 1. Choosing the Right UI Framework

| Framework | Best For |
|-----------|----------|
| **JavaFX** | Modern, styled desktop apps; data dashboards; business UIs; apps needing CSS theming and animation |
| **LWJGL** | Games, simulations, real-time 3D/2D rendering, OpenGL/Vulkan/OpenAL access |
| **Swing** | Legacy apps, IDE-style tooling, when JavaFX isn't viable; pair with FlatLaf for a modern look |

You may combine them: Swing + LWJGL (embed GLCanvas), or JavaFX + LWJGL (embed a `Canvas`/offscreen buffer).

---

## 2. Maven POM Fundamentals

### 2.1 Java Version

Always set source and target explicitly. Java 17+ is recommended for modern JavaFX and LWJGL 3.

```xml
<properties>
  <maven.compiler.source>17</maven.compiler.source>
  <maven.compiler.target>17</maven.compiler.target>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
```

### 2.2 Required Plugins

Always include these three plugins in `<build><plugins>`:

```xml
<!-- Compile -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
</plugin>

<!-- Fat JAR / shading (for Swing or non-modular apps) -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.3</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.example.Main</mainClass>
          </transformer>
        </transformers>
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>

<!-- Resources -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-resources-plugin</artifactId>
  <version>3.3.1</version>
</plugin>
```

---

## 3. JavaFX Setup

### 3.1 Dependencies

```xml
<properties>
  <javafx.version>21.0.3</javafx.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-controls</artifactId>
    <version>${javafx.version}</version>
  </dependency>
  <dependency>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-fxml</artifactId>
    <version>${javafx.version}</version>
  </dependency>
  <!-- Optional: charts, media, web -->
  <dependency>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-media</artifactId>
    <version>${javafx.version}</version>
  </dependency>
</dependencies>
```

### 3.2 JavaFX Maven Plugin (required for `mvn javafx:run`)

```xml
<plugin>
  <groupId>org.openjfx</groupId>
  <artifactId>javafx-maven-plugin</artifactId>
  <version>0.0.8</version>
  <configuration>
    <mainClass>com.example/com.example.Main</mainClass>
    <!-- Format: module-name/fully.qualified.MainClass -->
  </configuration>
</plugin>
```

### 3.3 Module System (`module-info.java`)

JavaFX apps should use the Java module system. Place `module-info.java` in `src/main/java/`:

```java
module com.example {
    requires javafx.controls;
    requires javafx.fxml;
    opens com.example to javafx.fxml;        // for FXML reflection
    exports com.example;
}
```

Add `requires javafx.media;` / `requires javafx.web;` as needed.

### 3.4 JavaFX Native Platform Handling

JavaFX automatically pulls platform natives through the openjfx artifacts — **no manual classifier needed** as long as you run on the same OS that built the JAR. For cross-platform fat JARs, add all platform classifiers:

```xml
<!-- Add these alongside the main dependency for fat-jar cross-platform support -->
<dependency>
  <groupId>org.openjfx</groupId>
  <artifactId>javafx-controls</artifactId>
  <version>${javafx.version}</version>
  <classifier>win</classifier>
</dependency>
<dependency>
  <groupId>org.openjfx</groupId>
  <artifactId>javafx-controls</artifactId>
  <version>${javafx.version}</version>
  <classifier>linux</classifier>
</dependency>
<dependency>
  <groupId>org.openjfx</groupId>
  <artifactId>javafx-controls</artifactId>
  <version>${javafx.version}</version>
  <classifier>mac</classifier>
</dependency>
<!-- Repeat for javafx-fxml, javafx-graphics, javafx-base, javafx-media as needed -->
```

**Important**: The `maven-shade-plugin` will merge these into one fat JAR correctly, but JavaFX loads `.dll`/`.so`/`.dylib` natives from the classpath at runtime. For non-modular fat JARs, use the `javafx-maven-plugin` for running, not `java -jar`.

---

## 4. LWJGL Setup

### 4.1 LWJGL BOM and Classifier Pattern

LWJGL uses **platform classifiers** for all native artifacts. Always declare the BOM and a `lwjgl.natives` property:

```xml
<properties>
  <lwjgl.version>3.3.4</lwjgl.version>

  <!-- Choose ONE based on target platform: -->
  <!-- natives-windows | natives-windows-x86 | natives-windows-arm64 -->
  <!-- natives-linux   | natives-linux-arm32 | natives-linux-arm64   -->
  <!-- natives-macos   | natives-macos-arm64                         -->
  <lwjgl.natives>natives-linux</lwjgl.natives>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.lwjgl</groupId>
      <artifactId>lwjgl-bom</artifactId>
      <version>${lwjgl.version}</version>
      <scope>import</scope>
      <type>pom</type>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 4.2 LWJGL Core + Common Modules

```xml
<dependencies>
  <!-- Core -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId>
    <classifier>${lwjgl.natives}</classifier></dependency>

  <!-- OpenGL -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId>
    <classifier>${lwjgl.natives}</classifier></dependency>

  <!-- GLFW (window + input) -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId>
    <classifier>${lwjgl.natives}</classifier></dependency>

  <!-- STB (image loading, font) -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-stb</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-stb</artifactId>
    <classifier>${lwjgl.natives}</classifier></dependency>

  <!-- OpenAL (optional audio) -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-openal</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-openal</artifactId>
    <classifier>${lwjgl.natives}</classifier></dependency>

  <!-- Vulkan (no natives needed — loaded by driver) -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-vulkan</artifactId></dependency>
</dependencies>
```

### 4.3 LWJGL Native Extraction

LWJGL extracts its `.dll`/`.so`/`.dylib` files from the JAR at runtime via `SharedLibraryLoader`. **No manual extraction needed** — just ensure the native JARs are on the classpath. For fat JARs, this works automatically with `maven-shade-plugin`.

If you ever see `UnsatisfiedLinkError`, verify:
1. The correct `${lwjgl.natives}` classifier matches the runtime OS/arch.
2. The native JAR is actually on the classpath (`mvn dependency:tree`).
3. No security manager is blocking temp-directory writes.

### 4.4 Multi-Platform LWJGL Fat JAR

For a JAR that ships all platforms (larger but convenient):

```xml
<!-- Declare all native classifiers for each module -->
<dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId>
  <classifier>natives-windows</classifier></dependency>
<dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId>
  <classifier>natives-linux</classifier></dependency>
<dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId>
  <classifier>natives-macos</classifier></dependency>
<!-- Repeat for lwjgl-opengl, lwjgl-glfw, lwjgl-stb, etc. -->
```

---

## 5. Swing Setup

### 5.1 No Maven Dependencies Required

Swing (`javax.swing`) is bundled with the JDK. Nothing to add to the POM except ensuring Java 8–21 is set.

### 5.2 FlatLaf — Modern Look and Feel (Highly Recommended)

Never ship a Swing app with the default Metal or Nimbus L&F. Use **FlatLaf**:

```xml
<dependency>
  <groupId>com.formdev</groupId>
  <artifactId>flatlaf</artifactId>
  <version>3.4.1</version>
</dependency>
<!-- Optional extras -->
<dependency>
  <groupId>com.formdev</groupId>
  <artifactId>flatlaf-extras</artifactId>
  <version>3.4.1</version>
</dependency>
<dependency>
  <groupId>com.formdev</groupId>
  <artifactId>flatlaf-intellij-themes</artifactId>
  <version>3.4.1</version>
</dependency>
```

Apply at startup, **before** any UI code:

```java
public static void main(String[] args) {
    FlatDarkLaf.setup();           // or FlatLightLaf, FlatMacDarkLaf, etc.
    SwingUtilities.invokeLater(() -> new MyApp().setVisible(true));
}
```

### 5.3 Swing Fat JAR

Use `maven-shade-plugin` (see §2.2). No special handling needed for Swing — no natives.

---

## 6. Custom Fonts in All Frameworks

Never rely on system fonts for a polished app. Bundle fonts as resources.

### JavaFX
```java
// In Application.start() or a CSS file:
Font.loadFont(getClass().getResourceAsStream("/fonts/MyFont-Regular.ttf"), 14);
```
In CSS: `font-family: 'My Font';`

### Swing / FlatLaf
```java
Font font = Font.createFont(Font.TRUETYPE_FONT,
    getClass().getResourceAsStream("/fonts/MyFont-Regular.ttf"));
GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
UIManager.put("defaultFont", font.deriveFont(14f));
```

### LWJGL (STB TrueType)
Use `STBTruetype` to rasterize glyphs into a texture atlas for GPU rendering.

---

## 7. Design Philosophy for Beautiful Desktop Applications

### 7.1 Commit to an Aesthetic Direction

Before writing a single line of UI code, decide on a clear visual identity:

- **Refined Dark**: Deep charcoals (#1a1a2e, #16213e), accent in amber or cyan, tight typography
- **Soft Light**: Off-white canvas (#fafaf7), warm grays, generous whitespace, serif headlines
- **High-Contrast Brutalist**: Black/white with one vivid accent, bold weights, stark grids
- **Glass / Depth**: Frosted backgrounds, layered blur panels, subtle depth shadows
- **Retro Terminal**: Dark green-on-black or amber-on-black, monospace everything, scanline textures

Pick one and be **ruthless** about staying in it. Mixed aesthetics look accidental, not eclectic.

### 7.2 Typography Rules

- Bundle 1–2 custom fonts. A display font + a UI/mono font is a strong pairing.
- Establish a type scale: e.g., 11 / 13 / 16 / 22 / 32 / 48px — never arbitrary sizes.
- Line height for readable body text: 1.5×. For UI labels: 1.2×.
- Letter-spacing on all-caps labels: +0.08–0.12em.
- Don't use more than 2 font families in one app.

### 7.3 Color System

```
Background base       → darkest or lightest anchor
Surface / card        → ±5–8% lighter/darker than base
Border / divider      → subtle, often 10–15% opacity of text color
Primary text          → high contrast (≥7:1 for WCAG AAA)
Secondary text        → 50–60% opacity of primary
Accent                → ONE vivid color; use sparingly for CTAs and focus states
Destructive / error   → desaturated red; never pure #ff0000
```

Use CSS variables (JavaFX), `UIManager.put()` (Swing/FlatLaf), or a `Colors` constants class (LWJGL).

### 7.4 Spacing System

Base unit: 8px. All spacing should be multiples of 4 or 8:

```
xs:  4px   (tight icon padding)
sm:  8px   (component internal)
md: 16px   (between related elements)
lg: 24px   (between sections)
xl: 40px   (major layout gaps)
```

### 7.5 Motion and Animation

**JavaFX**: Use `Timeline`, `TranslateTransition`, `FadeTransition`. Ease-out for entrances (200–350ms), ease-in for exits (150–250ms). Avoid bounce unless playful.

```java
FadeTransition ft = new FadeTransition(Duration.millis(280), node);
ft.setFromValue(0);
ft.setToValue(1);
ft.setInterpolator(Interpolator.EASE_OUT);
ft.play();
```

**Swing**: Use `javax.swing.Timer` for manual interpolation or the **Trident** / **Radiance** animation library.

**LWJGL**: Animate with delta-time in your render loop. Smooth lerp:
```java
current = current + (target - current) * (1 - Math.pow(0.001, deltaTime));
```

**Rules**: One page-load animation > scattered micro-interactions. Hover states should respond in ≤ 100ms. Loading spinners must be smooth (60fps).

### 7.6 Depth and Shadow

In JavaFX, simulate shadows with CSS:
```css
-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0.1, 0, 4);
```

In Swing with FlatLaf:
```java
panel.putClientProperty("JComponent.outline", "error"); // built-in accents
// Or paint custom shadows in paintComponent() using Graphics2D + AlphaComposite
```

In LWJGL: render shadow maps or use simple quad rendering with alpha-blended shadow textures.

### 7.7 Icons

- Use **vector icons** always (SVG rendered to JavaFX `ImageView` via Batik, or icon fonts like Material Icons).
- In LWJGL: pack icon sprites into a texture atlas, render as quads.
- In Swing/FlatLaf: use **FlatSVGIcon** from `flatlaf-extras` — crisp at any DPI.
- Size icons to the grid: 16×16, 20×20, 24×24. Never odd sizes.

### 7.8 HiDPI / Retina

- JavaFX: HiDPI is automatic. Use `Canvas.getGraphicsContext2D().setImageSmoothing(false)` for pixel art.
- Swing: set `System.setProperty("sun.java2d.uiScale", "2")` or let FlatLaf handle it.
- LWJGL: query `glfwGetWindowContentScale()` and multiply your framebuffer size.

---

## 8. Project Structure

```
my-app/
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   ├── module-info.java        ← JavaFX apps
│       │   └── com/example/
│       │       ├── Main.java
│       │       ├── ui/                 ← controllers, views
│       │       └── core/              ← business logic (no UI deps)
│       └── resources/
│           ├── com/example/
│           │   ├── views/             ← .fxml files
│           │   └── styles/            ← .css files
│           ├── fonts/                 ← bundled .ttf/.otf
│           └── icons/                 ← .svg or .png sprites
└── target/
```

---

## 9. Common Pitfalls and Fixes

| Problem | Cause | Fix |
|---------|-------|-----|
| `UnsatisfiedLinkError` (LWJGL) | Wrong `lwjgl.natives` classifier | Match OS + arch: `natives-linux-arm64` on ARM Linux |
| JavaFX app hangs after `mvn package` fat JAR | Module system conflict | Use `javafx-maven-plugin` to run, or use `jlink` for distribution |
| Blurry icons on Retina (Swing) | Raster images at 1× | Use FlatSVGIcon or provide @2x assets |
| FlatLaf not applying | L&F set after component creation | Call `FlatDarkLaf.setup()` **before** any `new JFrame()` |
| OpenGL context error (LWJGL) | Calling GL methods before `GL.createCapabilities()` | Always call `GL.createCapabilities()` right after making context current |
| FXML `LoadException` | Controller not opened in `module-info.java` | Add `opens com.example.ui to javafx.fxml;` |
| Fat JAR missing natives | shade plugin filter too aggressive | Ensure `*.dll`, `*.so`, `*.dylib` are not excluded in shade filters |

---

## 10. Quick-Start Templates

### JavaFX Minimal App
```java
public class Main extends Application {
    @Override
    public void start(Stage stage) {
        var label = new Label("Hello, World");
        label.setStyle("-fx-font-size: 24px; -fx-text-fill: white;");
        var root = new StackPane(label);
        root.setStyle("-fx-background-color: #1a1a2e;");
        stage.setScene(new Scene(root, 800, 600));
        stage.setTitle("My App");
        stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
```

### LWJGL Minimal OpenGL Window
```java
public class Main {
    public static void main(String[] args) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(800, 600, "LWJGL App", NULL, NULL);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // vsync
        GL.createCapabilities();
        while (!glfwWindowShouldClose(window)) {
            glClearColor(0.1f, 0.1f, 0.18f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
```

### Swing + FlatLaf Minimal App
```java
public class Main {
    public static void main(String[] args) {
        FlatDarkLaf.setup();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("My Swing App");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);
            JLabel label = new JLabel("Hello, World", SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(24f));
            frame.add(label);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
```

---

## 11. Distribution and Packaging

- **jpackage** (Java 14+): Creates OS-native installers (.exe, .dmg, .deb). Preferred for production.
  ```
  jpackage --input target/ --main-jar app.jar --main-class com.example.Main \
           --name MyApp --type dmg
  ```
- **jlink**: Creates a minimal custom JRE + app bundle. Best for JavaFX modules.
- **Fat JAR**: Simplest for dev/testing. Works well for Swing and LWJGL. Not recommended for JavaFX in production.
- For LWJGL apps, all natives are embedded in the fat JAR and auto-extracted — no extra steps for end users.

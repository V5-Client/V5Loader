#version 150 core

out vec4 fragColor;

uniform vec2 resolution;
uniform float time;
uniform vec2 mouse;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 6; i++) {
        v += noise(p) * a;
        p = p * 2.02 + vec2(31.7, 19.3);
        a *= 0.52;
    }
    return v;
}

float ridge(float n) {
    n = abs(n * 2.0 - 1.0);
    return 1.0 - n;
}

float ridgedFbm(vec2 p) {
    float v = 0.0;
    float a = 0.55;
    float prev = 1.0;
    for (int i = 0; i < 5; i++) {
        float n = ridge(noise(p));
        n *= n;
        v += n * a * prev;
        prev = clamp(n * 1.8, 0.0, 1.0);
        p = p * 2.05 + vec2(17.2, 9.1);
        a *= 0.55;
    }
    return v;
}

vec2 warp(vec2 p, float t) {
    vec2 q = vec2(
        fbm(p * 0.95 + vec2(0.0,  t * 0.22)),
        fbm(p * 0.95 + vec2(5.2, -t * 0.18))
    );

    vec2 r = vec2(
        fbm(p * 1.80 + q * 2.3 + vec2( 1.7, -0.8) + t * 0.11),
        fbm(p * 1.80 + q * 2.3 + vec2(-3.4,  2.1) - t * 0.09)
    );

    return p + (q - 0.5) * 0.55 + (r - 0.5) * 0.28;
}

vec2 contourRibbon(float v, float repeats, float px, float thicknessPx) {
    float cell = abs(fract(v * repeats) - 0.5);

    float aa = max(fwidth(v * repeats), px * 0.5);
    float core = 1.0 - smoothstep(aa * thicknessPx, aa * (thicknessPx + 1.2), cell);
    float glow = 1.0 - smoothstep(aa * (thicknessPx + 3.0), aa * (thicknessPx + 7.0), cell);

    return vec2(core, glow);
}

void main() {
    vec2 uv = gl_FragCoord.xy / resolution.xy;
    float aspect = resolution.x / resolution.y;
    float px = 1.0 / min(resolution.x, resolution.y);

    vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);

    vec2 mouseUv = vec2(mouse.x / resolution.x, 1.0 - mouse.y / resolution.y);
    vec2 mouseP = vec2((mouseUv.x - 0.5) * aspect, mouseUv.y - 0.5);

    float t = time * 0.18;

    vec2 cam = p;
    cam += vec2(sin(t * 0.60), cos(t * 0.43)) * 0.015;

    vec2 w0 = warp(cam * 0.95, t * 0.8);
    vec2 w1 = warp(cam * 1.50 + vec2(2.0, -1.4), -t * 0.9);
    vec2 w2 = warp(cam * 2.35 + vec2(-3.4, 2.7), t * 1.1);

    float f0 = ridgedFbm(w0 * 1.2 + vec2(t * 0.18, -t * 0.10));
    float f1 = ridgedFbm(w1 * 1.4 + vec2(-t * 0.26, t * 0.16));
    float f2 = fbm(w2 * 2.2 + vec2(t * 0.35, t * 0.24));

    vec3 topCol    = vec3(0.050, 0.115, 0.220);
    vec3 midCol    = vec3(0.018, 0.042, 0.095);
    vec3 bottomCol = vec3(0.006, 0.010, 0.026);

    float vgrad = smoothstep(-0.65, 0.75, p.y);
    vec3 color = mix(bottomCol, midCol, vgrad);
    color = mix(color, topCol, smoothstep(0.15, 0.95, vgrad));

    float mass0 = smoothstep(0.22, 0.82, f0 + p.y * 0.18 + 0.08);
    float mass1 = smoothstep(0.28, 0.84, f1 - p.y * 0.10);
    float mass2 = smoothstep(0.40, 0.86, f2 + f1 * 0.18);

    vec3 layerA = vec3(0.10, 0.30, 0.62);
    vec3 layerB = vec3(0.18, 0.48, 0.78);
    vec3 layerC = vec3(0.42, 0.72, 0.95);

    color += layerA * mass0 * 0.18;
    color += layerB * mass1 * 0.12;
    color += layerC * mass2 * 0.05;

    float lv0 = f0 + p.y * 0.23 + f2 * 0.08;
    float lv1 = f1 - p.y * 0.10 + f0 * 0.12;
    float lv2 = f2 + f1 * 0.20;

    float seamFreq0 = 4.5;
    float seamFreq1 = 6.0;
    float seamFreq2 = 8.5;

    float c0 = abs(fract(lv0 * seamFreq0) - 0.5);
    float c1 = abs(fract(lv1 * seamFreq1) - 0.5);
    float c2 = abs(fract(lv2 * seamFreq2) - 0.5);

    float a0 = max(fwidth(lv0 * seamFreq0), px * 0.45);
    float a1 = max(fwidth(lv1 * seamFreq1), px * 0.45);
    float a2 = max(fwidth(lv2 * seamFreq2), px * 0.45);

    float line0 = (1.0 - smoothstep(a0 * 0.55, a0 * 1.25, c0)) * mass0;
    float line1 = (1.0 - smoothstep(a1 * 0.55, a1 * 1.25, c1)) * mass1;
    float line2 = (1.0 - smoothstep(a2 * 0.55, a2 * 1.25, c2)) * mass2;

    float glow0 = (1.0 - smoothstep(a0 * 1.8, a0 * 4.5, c0)) * mass0;
    float glow1 = (1.0 - smoothstep(a1 * 1.8, a1 * 4.5, c1)) * mass1;
    float glow2 = (1.0 - smoothstep(a2 * 1.8, a2 * 4.5, c2)) * mass2;

    color += vec3(0.08, 0.16, 0.30) * glow0 * 0.04;
    color += vec3(0.10, 0.22, 0.38) * glow1 * 0.035;
    color += vec3(0.16, 0.30, 0.46) * glow2 * 0.02;

    color += vec3(0.18, 0.42, 0.82) * line0 * 0.04;
    color += vec3(0.26, 0.58, 0.92) * line1 * 0.03;
    color += vec3(0.72, 0.88, 1.00) * line2 * 0.01;

    float edge0 = smoothstep(0.10, 0.55, abs(f0 - f1));
    float edge1 = smoothstep(0.08, 0.42, abs(f1 - f2));
    color += vec3(0.10, 0.25, 0.52) * edge0 * 0.16;
    color += vec3(0.20, 0.46, 0.78) * edge1 * 0.12;

    float sweep = sin((p.x * 1.7 - p.y * 0.8) * 4.0 + t * 1.8 + f0 * 2.0);
    sweep = smoothstep(0.55, 0.95, sweep);
    color += vec3(0.08, 0.16, 0.30) * sweep * (0.08 + 0.10 * mass1);

    float md = length(p - mouseP);
    float spotlight = smoothstep(0.55, 0.0, md);
    color += vec3(0.18, 0.28, 0.42) * spotlight * 0.12;

    vec2 sparkSpace = w1 * 3.2 + vec2(t * 0.22, -t * 0.08);
    vec2 sparkCell = floor(sparkSpace * 6.0);
    vec2 sparkLocal = fract(sparkSpace * 6.0) - 0.5;
    float sparkRnd = hash(sparkCell);

    float spark = 0.0;
    if (sparkRnd > 0.972) {
        float d = length(sparkLocal);
        spark = 1.0 - smoothstep(0.05, 0.14, d);
        spark *= 0.55 + 0.45 * sin(time * 2.0 + sparkRnd * 40.0);
    }

    float streak = 1.0 - smoothstep(0.03, 0.16, length(vec2(sparkLocal.x * 0.55, sparkLocal.y)));
    spark = max(spark, streak * step(0.986, sparkRnd) * 0.55);

    color += vec3(0.65, 0.86, 1.00) * spark * (0.18 + 0.22 * mass1);

    color = pow(max(color, 0.0), vec3(0.95));

    float grain = hash(gl_FragCoord.xy + time * 13.7) - 0.5;
    color += grain * 0.010;

    float vignette = smoothstep(1.18, 0.24, length(vec2(p.x * 0.95, p.y * 1.08)));
    color *= vignette;
    color *= 0.96;

    fragColor = vec4(color, 1.0);
}

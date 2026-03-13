#version 150 core

out vec4 fragColor;

uniform vec2 resolution;
uniform float time;
uniform vec2 mouse;

mat2 rot(float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return mat2(c, -s, s, c);
}

float lineMask(float coordinate, float density, float width) {
    float scaled = coordinate * density;
    float cell = abs(fract(scaled) - 0.5);
    float aa = max(fwidth(scaled), 0.0008);
    return 1.0 - smoothstep(aa * width, aa * (width + 1.6), cell);
}

float hash(vec2 p) {
    p = fract(p * vec2(234.34, 435.34));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / resolution.xy;
    vec2 p = uv * 2.0 - 1.0;
    p.x *= resolution.x / resolution.y;

    vec2 mouseUv = mouse / resolution;
    vec2 drift = (mouseUv - 0.5) * vec2(0.7, -0.45);
    float t = time * 0.35;

    vec2 skyP = (p + drift * 0.2) * rot(sin(t * 0.45) * 0.08);
    float horizon = smoothstep(-0.45, 0.75, skyP.y);

    vec3 color = mix(vec3(0.020, 0.012, 0.050), vec3(0.300, 0.040, 0.110), horizon);
    color = mix(color, vec3(0.980, 0.280, 0.140), smoothstep(-0.05, 0.85, skyP.y) * 0.45);

    vec2 sunP = skyP - vec2(0.0, 0.16);
    float sun = smoothstep(0.52, 0.0, length(sunP));
    float sunGlow = smoothstep(0.95, 0.0, length(sunP));
    float bandMask = lineMask(sunP.y + t * 0.03, 15.0, 0.55) * smoothstep(0.48, 0.05, length(sunP));

    color += vec3(1.000, 0.420, 0.120) * sun * 0.85;
    color += vec3(1.000, 0.180, 0.160) * sunGlow * 0.25;
    color -= vec3(0.180, 0.020, 0.070) * bandMask * 0.7;

    float beam = exp(-7.0 * abs(skyP.y + 0.08 * sin(skyP.x * 4.5 - t * 2.2) + 0.1));
    color += vec3(1.000, 0.240, 0.180) * beam * 0.26;

    float groundMask = 1.0 - smoothstep(-0.42, -0.04, p.y);
    float depth = 1.0 / max(-p.y + 0.08, 0.08);
    float vertical = lineMask((p.x + drift.x * 0.35) * depth, 7.5, 0.85) * groundMask;
    float horizontal = lineMask(depth + t * 1.6, 7.0, 0.95) * groundMask;

    color += vec3(0.980, 0.150, 0.170) * vertical * 0.18;
    color += vec3(1.000, 0.340, 0.170) * horizontal * 0.24;
    color += vec3(0.200, 0.030, 0.100) * groundMask * 0.15;

    float starField = step(0.9975, hash(floor((uv + vec2(t * 0.01, 0.0)) * vec2(220.0, 130.0))));
    starField *= smoothstep(0.25, 0.95, uv.y);
    color += vec3(1.000, 0.820, 0.720) * starField * 0.9;

    float vignette = smoothstep(1.28, 0.18, length(vec2(p.x * 0.92, p.y * 1.08)));
    color *= vignette;
    color = pow(max(color, 0.0), vec3(0.92));

    fragColor = vec4(color, 1.0);
}

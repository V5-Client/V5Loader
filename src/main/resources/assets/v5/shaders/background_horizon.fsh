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

    float t = time * 0.35;

    vec2 skyP = p * rot(sin(t * 0.45) * 0.08);
    float horizon = smoothstep(-0.45, 0.75, skyP.y);

    vec3 color = mix(vec3(0.015, 0.008, 0.055), vec3(0.300, 0.060, 0.440), horizon);
    color = mix(color, vec3(0.950, 0.300, 1.000), smoothstep(-0.05, 0.85, skyP.y) * 0.35);

    vec2 sunP = skyP - vec2(0.0, 0.16);
    float sun = smoothstep(0.52, 0.0, length(sunP));
    float sunGlow = smoothstep(0.95, 0.0, length(sunP));
    float bandMask = lineMask(sunP.y + t * 0.03, 15.0, 0.55) * smoothstep(0.48, 0.05, length(sunP));

    color += vec3(1.000, 0.270, 0.940) * sun * 0.85;
    color += vec3(0.950, 0.360, 1.000) * sunGlow * 0.25;
    color -= vec3(0.100, 0.020, 0.160) * bandMask * 0.7;

    float beam = exp(-7.0 * abs(skyP.y + 0.08 * sin(skyP.x * 4.5 - t * 2.2) + 0.1));
    color += vec3(0.800, 0.240, 1.000) * beam * 0.26;

    float groundMask = 1.0 - smoothstep(-0.42, -0.04, p.y);
    float depth = 1.0 / max(-p.y + 0.08, 0.08);
    float vertical = lineMask(p.x * depth, 7.5, 0.85) * groundMask;
    float horizontal = lineMask(depth + t * 1.6, 7.0, 0.95) * groundMask;

    color += vec3(1.000, 0.220, 0.900) * vertical * 0.20;
    color += vec3(0.460, 0.220, 1.000) * horizontal * 0.24;
    color += vec3(0.140, 0.020, 0.220) * groundMask * 0.15;

    float starField = step(0.9975, hash(floor((uv + vec2(t * 0.01, 0.0)) * vec2(220.0, 130.0))));
    starField *= smoothstep(0.25, 0.95, uv.y);
    color += vec3(0.880, 0.780, 1.000) * starField * 0.9;

    float vignette = smoothstep(1.28, 0.18, length(vec2(p.x * 0.92, p.y * 1.08)));
    color *= vignette;
    color = pow(max(color, 0.0), vec3(0.92));

    fragColor = vec4(color, 1.0);
}

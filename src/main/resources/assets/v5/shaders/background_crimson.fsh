#version 150 core

out vec4 fragColor;

uniform vec2 resolution;
uniform float time;
uniform vec2 mouse;

void main() {
    vec2 u = gl_FragCoord.xy;
    vec2 v = resolution.xy;
    u = 0.12 * (u + u - v) / v.y;

    vec4 o = vec4(1.0, 2.0, 3.0, 0.0);
    vec4 z = o;
    float a = 0.5;
    float t = time;

    for (float i = 1.0; i < 19.0; i += 1.0) {
        o += (1.0 + cos(z + t))
            / length((1.0 + i * dot(v, v))
            * sin(1.5 * u / (0.5 - dot(u, u)) - 9.0 * u.yx + t));

        t += 1.0;
        a += 0.03;
        v = cos(t - 7.0 * u * pow(a, i)) - 5.0 * u;

        u += tanh(
            40.0 * dot(
                u *= mat2(cos(i + 0.02 * t - z.wxzw * 11.0)),
                u
            ) * cos(100.0 * u.yx + t)
        ) / 200.0
            + 0.2 * a * u
            + cos(4.0 / exp(dot(o, o) / 100.0) + t) / 300.0;
    }

    o = 25.6 / (min(o, 13.0) + 164.0 / o) - dot(u, u) / 250.0;

    float brightness = (o.r + o.g + o.b) / 4.0;
    brightness = pow(clamp(brightness, 0.0, 1.0), 0.95);

    vec3 shadowColor = vec3(0.15, 0.07, 0.07);
    vec3 highlightColor = vec3(1.2, 0.35, 0.35);
    vec3 color = mix(shadowColor, highlightColor, brightness);

    float vignette = smoothstep(1.35, 0.12, length(u * vec2(0.72, 0.95)));
    color *= vignette;

    fragColor = vec4(color, 1.0);
}

#version 330

// Draws one eye's image into its region of the screen without a vertex buffer.
// gl_VertexID / 6 selects the region: bit 0 = right half, bit 1 = letterbox (half height).
out vec2 texCoord;

const int CORNERS[6] = int[6](0, 1, 2, 2, 1, 3);

void main() {
    int region = gl_VertexID / 6;
    int corner = CORNERS[gl_VertexID % 6];
    vec2 uv = vec2(corner & 1, corner >> 1);

    float left = (region & 1) == 0 ? -1.0 : 0.0;
    float halfHeight = (region & 2) == 0 ? 1.0 : 0.5;
    gl_Position = vec4(left + uv.x, (uv.y * 2.0 - 1.0) * halfHeight, 0.0, 1.0);
    texCoord = uv;
}

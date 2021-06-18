#version 450 core
#extension GL_ARB_separate_shader_objects: enable

layout(location = 0) out vec4 fColor;
layout(set=1, binding=0) uniform sampler2D sTexture;

layout(location = 0) in VertexData {
    vec4 Color;
    vec2 UV;
} Vertex;

void main()
{
    fColor = Vertex.Color * texture(sTexture, Vertex.UV.st);
}
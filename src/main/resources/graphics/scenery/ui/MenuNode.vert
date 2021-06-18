#version 450 core
#extension GL_ARB_separate_shader_objects: enable

layout(location = 0) in vec2 vertexPosition;
layout(location = 1) in vec2 vertexTexCoord;
layout(location = 2) in vec4 color;

//out gl_PerVertex { vec4 gl_Position; };
layout(location = 0) out VertexData {
    vec4 Color;
    vec2 UV;
} Vertex;

layout(set = 0, binding = 0) uniform ShaderProperties {
    vec2 uScale;
    vec2 uTranslate;
};

layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;

void main()
{
    Vertex.Color = color;
    Vertex.UV = vertexTexCoord;
    gl_Position = vec4(vertexPosition * uScale + uTranslate, 0, 1);
}

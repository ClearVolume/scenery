#version 450 core
#extension GL_ARB_separate_shader_objects: enable
#extension GL_EXT_control_flow_attributes : enable

// DSSDO method adapted from DSSDO RenderMonkey example, https://github.com/kayru/dssdo
// MIT License, Copyright (c) 2011 Yuriy O'Donnell
// Original reference for SSDO: Ritschel, et al.
// https://people.mpi-inf.mpg.de/~ritschel/Papers/SSDO.pdf

#define PI 3.14159265359

layout(set = 3, binding = 0) uniform sampler2D InputNormalsMaterial;
layout(set = 3, binding = 1) uniform sampler2D InputDiffuseAlbedo;
layout(set = 3, binding = 2) uniform sampler2D InputZBuffer;

layout(location = 0) out float FragColor;
layout(location = 0) in VertexData {
    vec2 textureCoord;
    mat4 projectionMatrix;
    mat4 viewMatrix;
    mat4 frustumVectors;
} Vertex;

layout(set = 0, binding = 0) uniform VRParameters {
    mat4 projectionMatrices[2];
    mat4 inverseProjectionMatrices[2];
    mat4 headShift;
    float IPD;
    int stereoEnabled;
} vrParameters;

layout(set = 1, binding = 0) uniform LightParameters {
    mat4 ViewMatrices[2];
    mat4 InverseViewMatrices[2];
    mat4 ProjectionMatrix;
    mat4 InverseProjectionMatrix;
    vec3 CamPosition;
};

layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;

layout(set = 2, binding = 0, std140) uniform ShaderParameters {
	int displayWidth;
	int displayHeight;
	float occlusionRadius;
	int occlusionSamples;
	float occlusionExponent;
    float maxDistance;
    float bias;
    int algorithm;
};

vec3 viewFromDepth(float depth, vec2 texcoord) {
    vec2 uv = (vrParameters.stereoEnabled ^ 1) * texcoord + vrParameters.stereoEnabled * vec2((texcoord.x - 0.5 * currentEye.eye) * 2.0, texcoord.y);

	mat4 invProjection = (vrParameters.stereoEnabled ^ 1) * InverseProjectionMatrix + vrParameters.stereoEnabled * vrParameters.inverseProjectionMatrices[currentEye.eye];
	mat4 invView = (vrParameters.stereoEnabled ^ 1) * InverseViewMatrices[0] + vrParameters.stereoEnabled * (InverseViewMatrices[currentEye.eye]);

#ifndef OPENGL
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth, 1.0);
#else
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
#endif
    vec4 viewSpacePosition = invProjection * clipSpacePosition;

    viewSpacePosition /= viewSpacePosition.w;
    return viewSpacePosition.xyz;
}

vec3 worldFromDepth(float depth, vec2 texcoord) {
    vec2 uv = (vrParameters.stereoEnabled ^ 1) * texcoord + vrParameters.stereoEnabled * vec2((texcoord.x - 0.5 * currentEye.eye) * 2.0, texcoord.y);

	mat4 invProjection = (vrParameters.stereoEnabled ^ 1) * InverseProjectionMatrix + vrParameters.stereoEnabled * vrParameters.inverseProjectionMatrices[currentEye.eye];
	mat4 invView = (vrParameters.stereoEnabled ^ 1) * InverseViewMatrices[0] + vrParameters.stereoEnabled * (InverseViewMatrices[currentEye.eye]);

#ifndef OPENGL
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth, 1.0);
#else
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
#endif
    vec4 viewSpacePosition = invProjection * clipSpacePosition;

    viewSpacePosition /= viewSpacePosition.w;
    vec4 world = invView * viewSpacePosition;
    return world.xyz;
}

// McGuire Noise -- https://www.shadertoy.com/view/4dS3Wd
float hash(float n) { return fract(sin(n) * 1e4); }
float hash(vec2 p) { return fract(1e4 * sin(17.0 * p.x + p.y * 0.1) * (0.1 + abs(sin(p.y * 13.0 + p.x)))); }

float noise1D(float x) {
    float i = floor(x);
    float f = fract(x);
    float u = f * f * (3.0 - 2.0 * f);
    return mix(hash(i), hash(i + 1.0), u);
}

vec2 OctWrap( vec2 v )
{
    vec2 ret;
    ret.x = (1-abs(v.y)) * (v.x >= 0 ? 1.0 : -1.0);
    ret.y = (1-abs(v.x)) * (v.y >= 0 ? 1.0 : -1.0);
    return ret.xy;
}

/*
Decodes the octahedron normal vector from it's two component form to return the normal with its three components. Uses the
property |x| + |y| + |z| = 1 and reverses the orthogonal projection performed while encoding.
*/
vec3 DecodeOctaH( vec2 encN )
{
    encN = encN * 2.0 - 1.0;
    vec3 n;
    n.z = 1.0 - abs( encN.x ) - abs( encN.y );
    n.xy = n.z >= 0.0 ? encN.xy : OctWrap( encN.xy );
    n = normalize( n );
    return n;
}

const vec2 poisson16[] = vec2[](
		vec2( -0.94201624,  -0.39906216 ),
		vec2(  0.94558609,  -0.76890725 ),
		vec2( -0.094184101, -0.92938870 ),
		vec2(  0.34495938,   0.29387760 ),
		vec2( -0.91588581,   0.45771432 ),
		vec2( -0.81544232,  -0.87912464 ),
		vec2( -0.38277543,   0.27676845 ),
		vec2(  0.97484398,   0.75648379 ),
		vec2(  0.44323325,  -0.97511554 ),
		vec2(  0.53742981,  -0.47373420 ),
		vec2( -0.26496911,  -0.41893023 ),
		vec2(  0.79197514,   0.19090188 ),
		vec2( -0.24188840,   0.99706507 ),
		vec2( -0.81409955,   0.91437590 ),
		vec2(  0.19984126,   0.78641367 ),
		vec2(  0.14383161,  -0.14100790 )
);

void main() {
    if(occlusionSamples == 0) {
        FragColor = 1.0;
        return;
    }

    vec2 textureCoord = gl_FragCoord.xy/vec2(displayWidth, displayHeight);
    // DSSDO might happen at a lower res, this shift is there to prevent border artifacts
    vec2 textureCoordSubpixelShifted = (gl_FragCoord.xy + vec2(0.25))/vec2(displayWidth, displayHeight);

	vec3 N = DecodeOctaH(texture(InputNormalsMaterial, textureCoordSubpixelShifted).rg);
	float Depth = texture(InputZBuffer, textureCoord).r;
    vec3 FragPos = worldFromDepth(Depth, textureCoord);
    vec2 filterRadius = vec2(occlusionRadius*25.0/displayWidth, occlusionRadius*25.0/displayHeight);

    float ambientOcclusion = 0.0f;

    // vanilla SSAO
    if(algorithm == 0) {
        [[unroll]] for (int i = 0; i < occlusionSamples;  ++i) {
            // sample at an offset specified by the current Poisson-Disk sample and scale it by a radius (has to be in Texture-Space)
            vec2 sampleTexCoord = textureCoord + (poisson16[i] * filterRadius);
            float sampleDepth = texture(InputZBuffer, sampleTexCoord).r;
            vec3 samplePos = worldFromDepth(sampleDepth, textureCoord);

            vec3 sampleDir = normalize(samplePos - FragPos);

            float NdotS = max(dot(N, sampleDir), 0.0);
            float VPdistSP = distance(FragPos, samplePos);

            float a = 1.0 - smoothstep(maxDistance, maxDistance * 2, VPdistSP);

            ambientOcclusion += a * NdotS;
        }

        ambientOcclusion /= float(occlusionSamples);
        ambientOcclusion = pow(ambientOcclusion, occlusionExponent);
    }

    //Alchemy SSAO algorithm
    else if (algorithm == 1) {
        float A = 0.0f;

        // Parameters from McGuire's paper
        float BiasDistance = 0.0001f;
        float Epsilon = 0.0001f;
        float IntensityScale = 0.2f;
        float Contrast = 1.0f;

        vec3 viewSpacePos = viewFromDepth(Depth, textureCoord);

        [[unroll]] for (int i = 0; i < occlusionSamples;  ++i) {
            vec2 sampleTexCoord = textureCoord + (poisson16[i] * (filterRadius));
            float sampleDepth = texture(InputZBuffer, sampleTexCoord).r;
            vec3 samplePos = worldFromDepth(sampleDepth, sampleTexCoord);
            vec3 sampleDir = samplePos - FragPos;

            float NdotV = max(dot(N, sampleDir), 0);
            float VdotV = max(dot(sampleDir, sampleDir), 0);
            float temp = max(0, NdotV + viewSpacePos.z*BiasDistance);
            temp /= (VdotV + Epsilon);
            A+=temp;
         }

         A /= occlusionSamples;
         A *= (2*IntensityScale);
         A = max(0, 1 - A);
         A = pow(A, Contrast);
         ambientOcclusion = A;
    }

    FragColor = 1.0 - ambientOcclusion;
}

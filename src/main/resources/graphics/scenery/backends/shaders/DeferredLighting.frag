#version 450 core
#extension GL_ARB_separate_shader_objects: enable

#define PI 3.14159265359

layout(location = 0) in VertexData {
    vec3 FragPosition;
    vec3 Normal;
    vec2 TexCoord;
} Vertex;

layout(location = 0) out vec4 FragColor;

layout(set = 3, binding = 0) uniform sampler2D InputNormalsMaterial;
layout(set = 3, binding = 1) uniform sampler2D InputDiffuseAlbedo;
layout(set = 3, binding = 2) uniform sampler2D InputZBuffer;
layout(set = 4, binding = 0) uniform sampler2D InputOcclusion;

struct Light {
	float Linear;
	float Quadratic;
	float Intensity;
	float Radius;
	vec4 Position;
  	vec4 Color;
};

const int MAX_NUM_LIGHTS = 1024;

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

layout(set = 2, binding = 0) uniform Matrices {
	mat4 ModelMatrix;
	mat4 NormalMatrix;
	int isBillboard;
} ubo;

layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;

layout(set = 5, binding = 0, std140) uniform ShaderProperties {
    float intensity;
    float lightRadius;
    int debugMode;
    vec3 worldPosition;
    vec3 emissionColor;
    int lightType;
};

layout(set = 6, binding = 0, std140) uniform ShaderParameters {
    int debugLights;
	int reflectanceModel;
	int displayWidth;
	int displayHeight;
};


float GGXDistribution(vec3 normal, vec3 halfway, float roughness) {
    float a = roughness*roughness;
    float aSquared = a*a;
    float NdotH = abs(dot(normal, halfway));
    float NdotH2 = NdotH*NdotH;

    float denom = ((NdotH2 * (aSquared - 1.0) + 1.0));
    return aSquared/(denom*denom*PI);
}

float GeometrySchlick(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r*r) / 8.0;

    return (NdotV)/(NdotV*(1.0 - k) + k);
}

float GeometrySmith(vec3 normal, vec3 view, vec3 light, float roughness) {
    float NdotV = abs(dot(normal, view));
    float NdotL = abs(dot(normal, light));

    return GeometrySchlick(NdotV, roughness) * GeometrySchlick(NdotL, roughness);
}

vec3 FresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

float rand(vec2 co){
    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

vec3 DecodeSpherical(vec2 In)
{
    vec2 ang = In.xy * 2.0 - 1.0;
    vec2 scth;
    scth.x = sin(ang.x * PI);
    scth.y = cos(ang.x * PI);
    vec2 scphi = vec2(sqrt(1 - ang.y*ang.y), ang.y);

    vec3 dec;
    dec.x = scth.y * scphi.x;
    dec.y = scth.x * scphi.x;
    dec.z = scphi.y;
    return dec;
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

vec3 worldFromDepth(float depth, vec2 texcoord, const mat4 invProjection, const mat4 invView) {
#ifndef OPENGL
    vec3 clipSpacePosition = vec3(texcoord * 2.0 - 1.0, depth);
#else
    vec3 clipSpacePosition = vec3(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0);
#endif
    vec4 viewSpacePosition = vec4(
            invProjection[0][0] * clipSpacePosition.x + invProjection[3][0],
            invProjection[1][1] * clipSpacePosition.y + invProjection[3][1],
            -1.0,
            invProjection[2][3] * clipSpacePosition.z + invProjection[3][3]);

    viewSpacePosition /= viewSpacePosition.w;
    vec4 world = invView * viewSpacePosition;
    return world.xyz;
}

vec3 viewFromDepth(float depth, vec2 texcoord, const mat4 invProjection) {
#ifndef OPENGL
    vec4 clipSpacePosition = vec4(texcoord * 2.0 - 1.0, depth, 1.0);
#else
    vec4 clipSpacePosition = vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
#endif
    vec4 viewSpacePosition = vec4(
                invProjection[0][0] * clipSpacePosition.x + invProjection[3][0],
                invProjection[1][1] * clipSpacePosition.y + invProjection[3][1],
                -1.0,
                invProjection[2][3] * clipSpacePosition.z + invProjection[3][3]);
    viewSpacePosition /= viewSpacePosition.w;
    return viewSpacePosition.xyz;
}


float SinPhi(float Ndotw, vec3 w) {
    float SinTheta = sqrt(max(0, 1 - Ndotw));

    if(Ndotw < 0.0001) {
        return 0.0;
    } else {
        return clamp(w.y/SinTheta, -1.0, 1.0);
    }
}

float CosPhi(float Ndotw, vec3 w) {
    float SinTheta = sqrt(max(0, 1 - Ndotw));

    if(Ndotw < 0.0001) {
        return 1.0;
    } else {
        return clamp(w.x/SinTheta, -1.0, 1.0);
    }
}

vec2 alphabeta(float NdotL, float NdotV) {
    vec2 ab = vec2(0.0);

    if(abs(NdotL) > abs(NdotV)) {
        ab = vec2(sqrt(max(0.0, 1 - NdotV)), sqrt(max(0.0, 1 - NdotL) / max(abs(NdotL), 0.0000001)));
    } else {
        ab = vec2(sqrt(max(0.0, 1 - NdotL)), sqrt(max(0.0, 1 - NdotV) / max(abs(NdotV), 0.0000001)));
    }

    return ab;
}

#define Point2 vec2
#define Point3 vec3
#define Vector2 vec2
#define Vector3 vec3
#define Vector4 vec4
#define float3 vec3
#define float2 vec2
#define Color3 vec3
#define int2 ivec2

float distanceSquared(vec2 a, vec2 b) { a -= b; return dot(a, a); }

void swap(inout float lhs, inout float rhs) {
	float tmp = lhs;
	lhs = rhs;
	rhs = tmp;
}

// Returns true if the ray hit something
bool traceScreenSpaceRay1
   (Point3          csOrigin,
    Vector3         csDirection,
    mat4x4          projectToPixelMatrix,
    mat4            invProjection,
    sampler2D       csZBuffer,
    float2          csZBufferSize,
    float           csZThickness,
    const in bool   csZBufferIsHyperbolic,
    float3          clipInfo,
    float           nearPlaneZ,
    float			stride,
    float           jitterFraction,
    float           maxSteps,
    in float        maxRayTraceDistance,
    out Point2      hitPixel,
    out int         hitLayer,
	out Point3		csHitPoint,
    out Color3      debugColor
    ) {
    debugColor = Color3(0);
    // Clip ray to a near plane in 3D (doesn't have to be *the* near plane, although that would be a good idea)
    float rayLength = ((csOrigin.z + csDirection.z * maxRayTraceDistance) > nearPlaneZ) ?
                        (nearPlaneZ - csOrigin.z) / csDirection.z :
                        maxRayTraceDistance;
	Point3 csEndPoint = csDirection * rayLength + csOrigin;

    // Project into screen space
    Vector4 H0 = projectToPixelMatrix * Vector4(csOrigin, 1.0);
    Vector4 H1 = projectToPixelMatrix * Vector4(csEndPoint, 1.0);

    // There are a lot of divisions by w that can be turned into multiplications
    // at some minor precision loss...and we need to interpolate these 1/w values
    // anyway.
    //
    // Because the caller was required to clip to the near plane,
    // this homogeneous division (projecting from 4D to 2D) is guaranteed
    // to succeed.
    float k0 = 1.0 / H0.w;
    float k1 = 1.0 / H1.w;

    // Switch the original points to values that interpolate linearly in 2D
    Point3 Q0 = csOrigin * k0;
    Point3 Q1 = csEndPoint * k1;

	// Screen-space endpoints
    Point2 P0 = H0.xy * k0;
    Point2 P1 = H1.xy * k1;

    // [Optional clipping to frustum sides here]

    // Initialize to off screen
    hitPixel = Point2(-1.0, -1.0);
    hitLayer = 0; // Only one layer

    // If the line is degenerate, make it cover at least one pixel
    // to avoid handling zero-pixel extent as a special case later
    P1 += Vector2((distanceSquared(P0, P1) < 0.0001) ? 0.01 : 0.0);

    Vector2 delta = P1 - P0;

    // Permute so that the primary iteration is in x to reduce
    // large branches later
    bool permute = (abs(delta.x) < abs(delta.y));
	if (permute) {
		// More-vertical line. Create a permutation that swaps x and y in the output
        // by directly swizzling the inputs.
		delta = delta.yx;
		P1 = P1.yx;
		P0 = P0.yx;
	}

	// From now on, "x" is the primary iteration direction and "y" is the secondary one
    float stepDirection = sign(delta.x);
    float invdx = stepDirection / delta.x;
    Vector2 dP = Vector2(stepDirection, invdx * delta.y);

    // Track the derivatives of Q and k
    Vector3 dQ = (Q1 - Q0) * invdx;
    float   dk = (k1 - k0) * invdx;

    // Because we test 1/2 a texel forward along the ray, on the very last iteration
    // the interpolation can go past the end of the ray. Use these bounds to clamp it.
    float zMin = min(csEndPoint.z, csOrigin.z);
    float zMax = max(csEndPoint.z, csOrigin.z);

    // Scale derivatives by the desired pixel stride
	dP *= stride; dQ *= stride; dk *= stride;

    // Offset the starting values by the jitter fraction
	P0 += dP * jitterFraction; Q0 += dQ * jitterFraction; k0 += dk * jitterFraction;

	// Slide P from P0 to P1, (now-homogeneous) Q from Q0 to Q1, and k from k0 to k1
    Point3 Q = Q0;
    float  k = k0;

	// We track the ray depth at +/- 1/2 pixel to treat pixels as clip-space solid
	// voxels. Because the depth at -1/2 for a given pixel will be the same as at
	// +1/2 for the previous iteration, we actually only have to compute one value
	// per iteration.
	float prevZMaxEstimate = csOrigin.z;
    float stepCount = 0.0;
    float rayZMax = prevZMaxEstimate, rayZMin = prevZMaxEstimate;
    float sceneZMax = rayZMax + 1e4;

    // P1.x is never modified after this point, so pre-scale it by
    // the step direction for a signed comparison
    float end = P1.x * stepDirection;

    // We only advance the z field of Q in the inner loop, since
    // Q.xy is never used until after the loop terminates.

    Point2 P;
	for (P = P0;
        ((P.x * stepDirection) <= end) &&
        (stepCount < maxSteps) &&
        ((rayZMax < sceneZMax - csZThickness) ||
            (rayZMin > sceneZMax)) &&
        (sceneZMax != 0.0);
        P += dP, Q.z += dQ.z, k += dk, stepCount += 1.0) {

        // The depth range that the ray covers within this loop
        // iteration.  Assume that the ray is moving in increasing z
        // and swap if backwards.  Because one end of the interval is
        // shared between adjacent iterations, we track the previous
        // value and then swap as needed to ensure correct ordering
        rayZMin = prevZMaxEstimate;

        // Compute the value at 1/2 step into the future
        rayZMax = (dQ.z * 0.5 + Q.z) / (dk * 0.5 + k);
        rayZMax = clamp(rayZMax, zMin, zMax);
		prevZMaxEstimate = rayZMax;

        // Since we don't know if the ray is stepping forward or backward in depth,
        // maybe swap. Note that we preserve our original z "max" estimate first.
        if (rayZMin > rayZMax) { swap(rayZMin, rayZMax); }

        // Camera-space z of the background
        hitPixel = permute ? P.yx : P;
        sceneZMax = texelFetch(csZBuffer, int2(hitPixel), 0).r;

        // This compiles away when csZBufferIsHyperbolic = false
        // UNUSED
        if (csZBufferIsHyperbolic) {
//            sceneZMax = reconstructCSZ(sceneZMax, clipInfo);
			sceneZMax = viewFromDepth(sceneZMax, hitPixel, invProjection).z;
        }
    } // pixel on ray

    // Undo the last increment, which ran after the test variables
    // were set up.
    P -= dP; Q.z -= dQ.z; k -= dk; stepCount -= 1.0;

    bool hit = (rayZMax >= sceneZMax - csZThickness) && (rayZMin <= sceneZMax);

    // If using non-unit stride and we hit a depth surface...
    if ((stride > 1) && hit) {
        // Refine the hit point within the last large-stride step

        // Retreat one whole stride step from the previous loop so that
        // we can re-run that iteration at finer scale
        P -= dP; Q.z -= dQ.z; k -= dk; stepCount -= 1.0;

        // Take the derivatives back to single-pixel stride
        float invStride = 1.0 / stride;
        dP *= invStride; dQ.z *= invStride; dk *= invStride;

        // For this test, we don't bother checking thickness or passing the end, since we KNOW there will
        // be a hit point. As soon as
        // the ray passes behind an object, call it a hit. Advance (stride + 1) steps to fully check this
        // interval (we could skip the very first iteration, but then we'd need identical code to prime the loop)
        float refinementStepCount = 0;

        // This is the current sample point's z-value, taken back to camera space
        prevZMaxEstimate = Q.z / k;
        rayZMin = prevZMaxEstimate;

        // Ensure that the FOR-loop test passes on the first iteration since we
        // won't have a valid value of sceneZMax to test.
        sceneZMax = rayZMin - 1e7;

        for (;
            (refinementStepCount <= stride*1.4) &&
            (rayZMin > sceneZMax) && (sceneZMax != 0.0);
            P += dP, Q.z += dQ.z, k += dk, refinementStepCount += 1.0) {

            rayZMin = prevZMaxEstimate;

            // Compute the ray camera-space Z value at 1/2 fine step (pixel) into the future
            rayZMax = (dQ.z * 0.5 + Q.z) / (dk * 0.5 + k);
            rayZMax = clamp(rayZMax, zMin, zMax);

            prevZMaxEstimate = rayZMax;
            rayZMin = min(rayZMax, rayZMin);

            hitPixel = permute ? P.yx : P;
            sceneZMax = texelFetch(csZBuffer, int2(hitPixel), 0).r;

            if (csZBufferIsHyperbolic) {
//                sceneZMax = reconstructCSZ(sceneZMax, clipInfo);
                sceneZMax = viewFromDepth(sceneZMax, hitPixel, invProjection).z;
            }
        }

        // Undo the last increment, which happened after the test variables were set up
        Q.z -= dQ.z; refinementStepCount -= 1;

        // Count the refinement steps as fractions of the original stride. Save a register
        // by not retaining invStride until here
        stepCount += refinementStepCount / stride;
      //  debugColor = vec3(refinementStepCount / stride);
    } // refinement

    Q.xy += dQ.xy * stepCount;
	csHitPoint = Q * (1.0 / k);

    // Support debugging. This will compile away if debugColor is unused
    if ((P.x * stepDirection) > end) {
        // Hit the max ray distance -> blue
        debugColor = vec3(0,0,1);
    } else if (stepCount >= maxSteps) {
        // Ran out of steps -> red
        debugColor = vec3(1,0,0);
    } else if (sceneZMax == 0.0) {
        // Went off screen -> yellow
        debugColor = vec3(1,1,0);
    } else {
        // Encountered a valid hit -> green
        // ((rayZMax >= sceneZMax - csZThickness) && (rayZMin <= sceneZMax))
        debugColor = vec3(0,1,0);
    }

    // Does the last point discovered represent a valid hit?
    return hit;
}


void main()
{
	/*mat4 viewMatrix = (vrParameters.stereoEnabled ^ 1) * ViewMatrices[0] + (vrParameters.stereoEnabled * ViewMatrices[currentEye.eye]);
    mat4 mv = viewMatrix * ubo.ModelMatrix;
    float halfW = displayWidth/2.0f;
    float halfH = displayHeight/2.0f;
    mat4 toPixel = mat4(
        halfW, 0.0f, 0.0f, halfW,
        0, -halfH, 0.0f, halfH,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f);

	mat4 projectionMatrix = (vrParameters.stereoEnabled ^ 1) * ProjectionMatrix + vrParameters.stereoEnabled * vrParameters.projectionMatrices[currentEye.eye];
	mat4 mvp = projectionMatrix*mv;*/

    vec2 textureCoord = gl_FragCoord.xy/vec2(displayWidth, displayHeight);
	vec2 uv = (vrParameters.stereoEnabled ^ 1) * textureCoord + vrParameters.stereoEnabled * vec2((textureCoord.x - 0.5 * currentEye.eye) * 2.0, textureCoord.y);

    mat4 invProjection = (vrParameters.stereoEnabled ^ 1) * InverseProjectionMatrix + vrParameters.stereoEnabled * vrParameters.inverseProjectionMatrices[currentEye.eye];
    mat4 invView = (vrParameters.stereoEnabled ^ 1) * InverseViewMatrices[0] + vrParameters.stereoEnabled * (InverseViewMatrices[currentEye.eye] );


	vec3 N = DecodeOctaH(texture(InputNormalsMaterial, textureCoord).rg);

	vec4 Albedo = texture(InputDiffuseAlbedo, textureCoord).rgba;
	float Specular = texture(InputDiffuseAlbedo, textureCoord).a;
	vec2 MaterialParams = texture(InputNormalsMaterial, textureCoord).ba;

	float Depth = texture(InputZBuffer, textureCoord).r;

    vec3 FragPos = worldFromDepth(Depth, uv, invProjection, invView);
    vec4 ambientOcclusion = texture(InputOcclusion, textureCoord).rrrr;

    vec3 cameraPosition = invView[3].xyz;

	float fragDist = length(FragPos - cameraPosition);

	vec3 lighting = vec3(0.0);

    vec3 L;
    float lightAttenuation = 0.0;

    if(lightType == 0) {
        L = (worldPosition.xyz - FragPos.xyz);
        float distance = length(L);
        L = normalize(L);

        lightAttenuation = clamp(1.0 - distance*distance/(lightRadius*lightRadius), 0.0, 1.0);
        lightAttenuation *= lightAttenuation;
    } else if(lightType == 2) {
        FragColor = vec4(intensity * Albedo.rgb * emissionColor.rgb, 1.0);
        return;
    } else {
        L = normalize(worldPosition.xyz);
        lightAttenuation = 1.0;
    }

	if(debugLights == 1) {
        FragColor = vec4(N, 1.0);
        return;
	}

	if(debugLights == 2) {
	    FragColor = vec4(FragPos, 1.0);
	    return;
	}

    vec3 V = normalize(cameraPosition - FragPos);
    vec3 H = normalize(L + V);

    if(isnan(N.x)) {
        N = L;
    }

    vec3 specular = vec3(0.0f);
    vec3 diffuse = vec3(0.0f);
    float lightOcclusion = ambientOcclusion.a;

    if(reflectanceModel == 1) {
        // Diffuse
        float NdotL = max(0.0, dot(N, L));

        vec3 R = reflect(-L, N);
        float NdotR = max(0.0, dot(R, V));
        float NdotH = max(0.0, dot(N, H));

        diffuse = NdotL * intensity * Albedo.rgb * emissionColor.rgb * lightOcclusion;

        if(NdotL > 0.0) {
            specular = pow(NdotH, (1.0-Specular)*4.0) * Albedo.rgb * emissionColor.rgb * intensity;
        }
    }

    // Oren-Nayar model for diffuse and Cook-Torrance for Specular
    else if(reflectanceModel == 0) {
        float roughness = MaterialParams.r * PI / 2.0;

        float LdotV = max(dot(L, V), 0.0);
        float NdotL = max(dot(L, N), 0.0);
        float NdotV = max(dot(N, V), 0.0);

        float sigma2 = roughness * roughness;
        float A = 1.0 - sigma2 / (2.0 * (sigma2 + 0.33));
        float B = 0.45 * sigma2 / (sigma2 + 0.09);

        vec2 ab = alphabeta(NdotL, NdotV);
        float m = max(0, CosPhi(NdotL, L) * CosPhi(NdotV, V) + SinPhi(NdotL, L) * CosPhi(NdotV, V));

        float L1 = NdotL / PI * (A + B * m * ab.x * ab.y);

        vec3 inputColor = intensity * emissionColor.rgb * Albedo.rgb * lightOcclusion;

        diffuse = inputColor * L1;

        if(Specular > 0.0 || MaterialParams.g > 0.0) {
            float metallic = MaterialParams.g;
            vec3 F0 = vec3(0.04);
            F0 = mix(F0, Albedo.rgb, metallic);

            float roughness = MaterialParams.r;

            float NDF = GGXDistribution(N, H, roughness);
            float G = GeometrySmith(N, V, L, roughness);
            vec3 F = FresnelSchlick(max(dot(H, V), 0.0), F0);

            vec3 BRDF = (NDF * G * F)/max(4.0 * abs(NdotL) * abs(NdotV), 0.001);

            vec3 kS = F;
            vec3 kD = (vec3(1.0) - kS);
            kD *= 1.0 - metallic;

            vec3 radiance = intensity * emissionColor.rgb;
            specular = (kD * Albedo.rgb / PI + BRDF) * radiance * NdotL;
        }
    }



    if(debugLights > 0) {
        if(debugLights == 3) {
            lighting = specular * lightAttenuation;
        } if(debugLights == 4) {
            lighting = diffuse * lightAttenuation;
        } if(debugLights == 5) {
            lighting = ambientOcclusion.rrr;
        } if(debugLights == 6) {
            lighting = vec3(lightOcclusion);
        } if(debugLights == 7) {
            lighting = Albedo.rgb;
        } if(debugLights == 8) {
            lighting = vec3(MaterialParams.rg, 0.0);
        }
    } else {
        lighting = (diffuse + specular) * lightAttenuation;
    }

    // check if occluded
    /*
    vec4 lNDC  = mvp*vec4(worldPosition.xyz, 1.0);
    lNDC = lNDC / lNDC.w;
    float depthAtLight = lNDC.z;
    vec2 texLight = lNDC.xy * 2.0 - vec2(1.0);
    int maxShadowSteps = 8;
    vec2 dir = textureCoord - texLight;
    dir /= maxShadowSteps;
    int intersects = 0;
    */

//    for(int i = 0; i < maxShadowSteps; i++) {
//        float d = texture(InputZBuffer, textureCoord + i*dir).r;
//        if(d < Depth) {
//            intersects++;
//        }
//    }

/*
	vec2 hit;
	vec3 point;
	vec3 debugColor;
	int layer;
	vec3 vsPos = viewFromDepth(Depth, textureCoord);
//	vsPos = vsPos/vsPos.w;

	vec4 vsL = viewMatrix*vec4(L, 1.0f);
	vsL = vsL/vsL.w;

    bool intersect = traceScreenSpaceRay1(vsPos.xyz, -1.0f * vsL.xyz,
        projectionMatrix, invProjection, InputZBuffer,
        vec2(displayWidth, displayHeight),
        0.1, true, vec3(0.0f), -0.1f, 1.0f, 3.0, 10.0, 20.0,
        hit, layer, point, debugColor);

	if(intersect) {
        lighting *= 0.1;
    }


	if(debugLights == 1) {
		lighting = vsPos.xyz;
//		lighting = debugColor;
	}
	*/

    FragColor = vec4(lighting, 1.0);
    gl_FragDepth = Depth;
}

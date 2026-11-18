/*
 * gti.dev: see gesture_weighting.h for the rationale of this policy.
 */

#include "suggest/policyimpl/gesture/gesture_weighting.h"

#include <cstring>

#include "defines.h"
#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/layout/proximity_info_state.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/policyimpl/typing/scoring_params.h"
#include "utils/char_utils.h"

namespace latinime {

namespace {
// A key whose cost is within this margin of the best key at a sampled point is
// still considered owned by the current letter. Mirrors the demotion used by
// ProximityInfoStateUtils::getMostProbableString.
const float GESTURE_MATCH_MARGIN = 0.15f;
// Upper bound on the number of sampled points a single letter may consume.
// Kept small so one letter cannot swallow the whole path.
const int GESTURE_MAX_ADVANCE = 5;
// Neutral cost used when no geometric probability is available for a key.
const float GESTURE_NEUTRAL_MATCH_COST = 0.2f;
} // namespace

const GestureWeighting GestureWeighting::sInstance;

float GestureWeighting::getMatchedCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode, DicNode_InputStateG *inputStateG) const {
    const ProximityInfoState *const pInfoState = traverseSession->getProximityInfoState(0);
    const ProximityInfo *const proximityInfo = traverseSession->getProximityInfo();
    const int sampledInputSize = pInfoState->size();
    const int pointIndex = dicNode->getInputIndex(0);
    const int nodeCodePoint = dicNode->getNodeCodePoint();
    const int baseLowerCodePoint = CharUtils::toBaseLowerCase(nodeCodePoint);
    const int keyIndex = proximityInfo
            ? proximityInfo->getKeyIndexOf(baseLowerCodePoint) : NOT_AN_INDEX;

    // Always fill the input state so the traversal advances by a variable
    // number of sampled points instead of the fixed one point used by typing.
    inputStateG->mNeedsToUpdateInputStateG = true;
    inputStateG->mPointerId = 0;
    inputStateG->mPrevCodePoint = nodeCodePoint;
    inputStateG->mTerminalDiffCost = 0.0f;

    // Guarantee forward progress even when the geometric data cannot be used.
    int endIndex = pointIndex + 1;
    float cost = 0.0f;

    if (proximityInfo && keyIndex != NOT_AN_INDEX && pointIndex >= 0
            && pointIndex < sampledInputSize) {
        int j = pointIndex;
        while (j < sampledInputSize && j - pointIndex < GESTURE_MAX_ADVANCE) {
            const float maxWeighting = static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
            float bestKeyCost = maxWeighting;
            int bestKey = NOT_AN_INDEX;
            const int keyCount = proximityInfo->getKeyCount();
            for (int k = 0; k < keyCount; ++k) {
                const float keyCost = pInfoState->getProbability(j, k);
                if (keyCost < bestKeyCost) {
                    bestKeyCost = keyCost;
                    bestKey = k;
                }
            }
            const float skipCost = pInfoState->getProbability(j, NOT_AN_INDEX);
            if (skipCost < bestKeyCost) {
                bestKeyCost = skipCost;
                bestKey = NOT_AN_INDEX;
            }
            const float currentKeyCost = pInfoState->getProbability(j, keyIndex);

            if (currentKeyCost < maxWeighting
                    && currentKeyCost <= bestKeyCost + GESTURE_MATCH_MARGIN) {
                // This sampled point belongs to the current letter.
                cost += currentKeyCost;
            } else if (bestKey == NOT_AN_INDEX) {
                // The finger is travelling between keys. Consume the skip point
                // but let the next letter start at the following point.
                cost += skipCost;
            } else {
                // Another key clearly owns this point, so the current letter ends here.
                break;
            }
            ++j;
        }
        if (j > endIndex) {
            endIndex = j;
        }
    } else if (pointIndex >= 0 && pointIndex < sampledInputSize) {
        // Key is not on the keyboard (or no geometry available). Fall back to
        // the typing sweet-spot distance as a safe spatial cost.
        cost = pInfoState->getPointToKeyLength(pointIndex, nodeCodePoint)
                * ScoringParams::DISTANCE_WEIGHT_LENGTH;
        if (cost <= 0.0f || cost >= static_cast<float>(MAX_VALUE_FOR_WEIGHTING)) {
            cost = GESTURE_NEUTRAL_MATCH_COST;
        }
    }
    // When pointIndex >= sampledInputSize this is a completion beyond the
    // traced path. Keep the zero spatial cost; CT_COMPLETION adds its own cost.

    if (endIndex > sampledInputSize) {
        endIndex = sampledInputSize;
    }
    inputStateG->mInputIndex = static_cast<int16_t>(endIndex);
    inputStateG->mRawLength = static_cast<float>(endIndex - pointIndex);
    if (pointIndex >= 0 && pointIndex < sampledInputSize) {
        inputStateG->mDoubleLetterLevel = pInfoState->getDoubleLetterLevel(pointIndex);
    }
    return cost;
}

float GestureWeighting::getSpaceOmissionCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode, DicNode_InputStateG *const inputStateG) const {
    // During a swipe the finger never lifts, so a word boundary is expressed by
    // a space omission without consuming an extra sampled point. Leave
    // inputStateG untouched (mNeedsToUpdateInputStateG stays false) so the
    // generic forwardInputIndex(0, 0) keeps the current point and the first
    // letter of the next word aligns to the same place on the path.
    return ScoringParams::SPACE_OMISSION_COST * traverseSession->getMultiWordCostMultiplier();
}

} // namespace latinime

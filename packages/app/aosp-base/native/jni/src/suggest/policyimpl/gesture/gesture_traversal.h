/*
 * gti.dev: gesture-aware traversal policy for real finger swipes.
 *
 * AOSP open source ships the whole geometric front-end (sampling, speed rates,
 * skip probabilities and search keys in ProximityInfoState) but the traversal
 * policy that consumes those points lives in the proprietary Google library.
 * This class implements the Traversal interface with gesture semantics:
 *  - two pointers are allowed (MAX_POINTER_COUNT_G), otherwise the geometric
 *    pass never initialises the sampled points;
 *  - proximity is decided by the search-key vectors of the sampled path, not
 *    by the nearest-key test used for tap typing;
 *  - the whole user input does not have to be consumed, because a swipe can
 *    end after the last letter of the word;
 *  - caches and spatial tolerances are larger because one letter spans
 *    several sampled points.
 */

#ifndef LATINIME_GESTURE_TRAVERSAL_H
#define LATINIME_GESTURE_TRAVERSAL_H

#include <cstdint>

#include "defines.h"
#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/dicnode/dic_node_vector.h"
#include "suggest/core/layout/proximity_info_state.h"
#include "suggest/core/layout/proximity_info_utils.h"
#include "suggest/core/policy/traversal.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/core/suggest_options.h"
#include "suggest/policyimpl/typing/scoring_params.h"
#include "utils/char_utils.h"

namespace latinime {

class GestureTraversal : public Traversal {
  public:
    static const GestureTraversal *getInstance() { return &sInstance; }

    // CRITICAL: a gesture needs both pointer states initialised. Returning
    // MAX_POINTER_COUNT (1) makes the native side treat the input as typing.
    AK_FORCE_INLINE int getMaxPointerCount() const {
        return MAX_POINTER_COUNT_G;
    }

    AK_FORCE_INLINE bool allowsErrorCorrections(const DicNode *const dicNode) const {
        // A gesture accumulates spatial cost over many sampled points, so the
        // typing threshold is far too tight. Keep the same test with a larger
        // tolerance (safe fallback derived from the typing policy).
        return dicNode->getNormalizedSpatialDistance()
                < GESTURE_NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT;
    }

    AK_FORCE_INLINE bool isOmission(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, const DicNode *const childDicNode,
            const bool allowsErrorCorrections) const {
        // Fallback to typing semantics: apostrophes and other intentional
        // omissions are still meaningful inside a swipe.
        if (!CORRECT_OMISSION) {
            return false;
        }
        const bool canConsiderOmission =
                allowsErrorCorrections || childDicNode->canBeIntentionalOmission();
        if (!canConsiderOmission) {
            return false;
        }
        const int inputSize = traverseSession->getInputSize();
        if (dicNode->isCompletion(inputSize)) {
            return false;
        }
        if (dicNode->canBeIntentionalOmission()) {
            return true;
        }
        const int point0Index = dicNode->getInputIndex(0);
        const int currentBaseLowerCodePoint =
                CharUtils::toBaseLowerCase(childDicNode->getNodeCodePoint());
        const int typedBaseLowerCodePoint =
                CharUtils::toBaseLowerCase(traverseSession->getProximityInfoState(0)
                        ->getPrimaryCodePointAt(point0Index));
        return (currentBaseLowerCodePoint != typedBaseLowerCodePoint);
    }

    AK_FORCE_INLINE bool isSpaceSubstitutionTerminal(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        if (!CORRECT_NEW_WORD_SPACE_SUBSTITUTION) {
            return false;
        }
        if (traverseSession->getSuggestOptions()->weightForLocale()
                < ScoringParams::LOCALE_WEIGHT_THRESHOLD_FOR_SPACE_SUBSTITUTION) {
            return false;
        }
        if (!canDoLookAheadCorrection(traverseSession, dicNode)) {
            return false;
        }
        const int point0Index = dicNode->getInputIndex(0);
        return dicNode->isTerminalDicNode()
                && traverseSession->getProximityInfoState(0)->hasSpaceProximity(point0Index);
    }

    AK_FORCE_INLINE bool isSpaceOmissionTerminal(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        if (!CORRECT_NEW_WORD_SPACE_OMISSION) {
            return false;
        }
        if (traverseSession->getSuggestOptions()->weightForLocale()
                < ScoringParams::LOCALE_WEIGHT_THRESHOLD_FOR_SPACE_OMISSION) {
            return false;
        }
        const int inputSize = traverseSession->getInputSize();
        if (dicNode->isCompletion(inputSize)) {
            return false;
        }
        if (!dicNode->isTerminalDicNode()) {
            return false;
        }
        const int16_t pointIndex = dicNode->getInputIndex(0);
        return pointIndex <= inputSize && !dicNode->isTotalInputSizeExceedingLimit()
                && !dicNode->shouldBeFilteredBySafetyNetForBigram();
    }

    AK_FORCE_INLINE bool shouldDepthLevelCache(
            const DicTraverseSession *const traverseSession) const {
        // Gestures consume a variable number of points per node, so the
        // depth-indexed typing cache would be invalid.
        return false;
    }

    AK_FORCE_INLINE bool shouldNodeLevelCache(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool canDoLookAheadCorrection(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        const int inputSize = traverseSession->getInputSize();
        return dicNode->canDoLookAheadCorrection(inputSize);
    }

    AK_FORCE_INLINE ProximityType getProximityType(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode,
            const DicNode *const childDicNode) const {
        // Gesture proximity comes from the sampled search-key vectors, not from
        // the tap-oriented proximity characters.
        const ProximityInfoState *const pInfoState =
                traverseSession->getProximityInfoState(0);
        const int pointIndex = dicNode->getInputIndex(0);
        if (pointIndex < 0 || pointIndex >= pInfoState->size()) {
            // Past the end of the traced path: no sampled point can match.
            return UNRELATED_CHAR;
        }
        return pInfoState->getProximityTypeG(pointIndex, childDicNode->getNodeCodePoint());
    }

    AK_FORCE_INLINE bool needsToTraverseAllUserInput() const {
        // A swipe usually continues past the last character of the intended word.
        return false;
    }

    AK_FORCE_INLINE float getMaxSpatialDistance() const {
        // Gesture sampling spans many keys, allow a much wider point-to-key radius.
        return GESTURE_MAX_SPATIAL_DISTANCE_FACTOR * ScoringParams::MAX_SPATIAL_DISTANCE;
    }

    AK_FORCE_INLINE int getDefaultExpandDicNodeSize() const {
        // More children are plausible because search keys cover a whole neighbourhood.
        return GESTURE_EXPAND_DIC_NODE_SIZE;
    }

    AK_FORCE_INLINE int getMaxCacheSize(const int inputSize, const float weightForLocale) const {
        if (inputSize <= 1) {
            return GESTURE_CACHE_SIZE_FACTOR
                    * ScoringParams::MAX_CACHE_DIC_NODE_SIZE_FOR_SINGLE_POINT;
        }
        if (weightForLocale < ScoringParams::LOCALE_WEIGHT_THRESHOLD_FOR_SMALL_CACHE_SIZE) {
            return GESTURE_CACHE_SIZE_FACTOR
                    * ScoringParams::MAX_CACHE_DIC_NODE_SIZE_FOR_LOW_PROBABILITY_LOCALE;
        }
        return GESTURE_CACHE_SIZE_FACTOR * ScoringParams::MAX_CACHE_DIC_NODE_SIZE;
    }

    AK_FORCE_INLINE int getTerminalCacheSize() const {
        return MAX_RESULTS;
    }

    AK_FORCE_INLINE bool isPossibleOmissionChildNode(
            const DicTraverseSession *const traverseSession, const DicNode *const parentDicNode,
            const DicNode *const dicNode) const {
        const ProximityType proximityType =
                getProximityType(traverseSession, parentDicNode, dicNode);
        if (!ProximityInfoUtils::isMatchOrProximityChar(proximityType)) {
            return false;
        }
        return true;
    }

    AK_FORCE_INLINE bool isGoodToTraverseNextWord(const DicNode *const dicNode,
            const int probability) const {
        // Fallback to the typing rule for space-aware multi-word gestures.
        if (probability < ScoringParams::THRESHOLD_NEXT_WORD_PROBABILITY) {
            return false;
        }
        const bool shortCappedWord = dicNode->getNodeCodePointCount()
                < ScoringParams::THRESHOLD_SHORT_WORD_LENGTH && dicNode->isFirstCharUppercase();
        return !shortCappedWord
                || probability >= ScoringParams::THRESHOLD_NEXT_WORD_PROBABILITY_FOR_CAPPED;
    }

  private:
    DISALLOW_COPY_AND_ASSIGN(GestureTraversal);
    static const bool CORRECT_OMISSION;
    static const bool CORRECT_NEW_WORD_SPACE_SUBSTITUTION;
    static const bool CORRECT_NEW_WORD_SPACE_OMISSION;

    // Gesture tuning constants.
    static const float GESTURE_NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT;
    static const float GESTURE_MAX_SPATIAL_DISTANCE_FACTOR;
    static const int GESTURE_EXPAND_DIC_NODE_SIZE;
    static const int GESTURE_CACHE_SIZE_FACTOR;

    static const GestureTraversal sInstance;

    GestureTraversal() {}
    ~GestureTraversal() {}
};
} // namespace latinime
#endif // LATINIME_GESTURE_TRAVERSAL_H

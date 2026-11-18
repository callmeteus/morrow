/*
 * gti.dev: see gesture_traversal.h for the rationale of this policy.
 */

#include "suggest/policyimpl/gesture/gesture_traversal.h"

namespace latinime {

const bool GestureTraversal::CORRECT_OMISSION = true;
const bool GestureTraversal::CORRECT_NEW_WORD_SPACE_SUBSTITUTION = true;
const bool GestureTraversal::CORRECT_NEW_WORD_SPACE_OMISSION = true;

const float GestureTraversal::GESTURE_NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT =
        4.0f * ScoringParams::NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT;
const float GestureTraversal::GESTURE_MAX_SPATIAL_DISTANCE_FACTOR = 4.0f;
const int GestureTraversal::GESTURE_EXPAND_DIC_NODE_SIZE =
        2 * DicNodeVector::DEFAULT_NODES_SIZE_FOR_OPTIMIZATION;
const int GestureTraversal::GESTURE_CACHE_SIZE_FACTOR = 2;

const GestureTraversal GestureTraversal::sInstance;

} // namespace latinime

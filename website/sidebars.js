/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @format
 */

const {
  fbContent,
  fbInternalOnly,
} = require('docusaurus-plugin-internaldocs-fb/internal');
module.exports = {
  mainSidebar: [
    // TODO: update content
    'intro/what-is-litho',
    'getting-started',
    {
      Tutorial: [
        'tutorial/overview',
        // TODO: add kotlin dep
        'tutorial/project-setup',
        'tutorial/first-components',
        'tutorial/introducing-layout',
        'tutorial/adding-state',
        'tutorial/building-lists',
      ],
    },
    'mainconcepts',
    'components-basics',
    {
      'Custom Views & Drawables (Primitives)': [
        'primitives/overview',
        'primitives/primitive-component',
        'primitives/primitive',
        'primitives/primitive-measuring',
        'primitives/primitive-controllers',
        'primitives/primitive-preallocation',
        'primitives/primitive-tracing',
      ],
    },
    {
      Props: ['props/common-props', 'props/treeprops'],
    },
    {
      'Layout & Styling (Flexbox)': [
        'layout/layout-intro',
        {
          'Size & Position Properties': [
            'layout/core-styles',
            'layout/flexbox-styles',
          ],
        },
        {
          'Distributing & Aligning Children': [
            'layout/flexbox-yoga',
            'layout/yoga-playground',
          ],
        },
        {
          'Custom Layout': ['layout/custom-layout', 'layout/custom-layout-rules'],
        },
        {
          'View Behavior & Interaction': [
            'layout/view-styles',
            'accessibility/accessibility',
          ],
        },
        {
          'Advanced Litho Features': [
            'layout/visibility-styles',
            'layout/dynamic-value-styles',
            'layout/binder-styles',
            'layout/incremental-mount',
          ],
        },
        'layout/troubleshooting',
      ],
      'Scrollable Lists & Grids': [
        'lazycollections/lazycollections',
        'lazycollections/lazycollections-layout',
        'lazycollections/lazycollections-interactions',
        'lazycollections/lazycollections-working-with-updates',
      ],
      'Managing State & Side Effects': [
        'state-and-side-effects/hooks-intro',
        'state-and-side-effects/use-state',
        'state-and-side-effects/use-effect',
        'state-and-side-effects/use-ref',
        'state-and-side-effects/use-cached',
        'state-and-side-effects/use-error-boundary',
        'state-and-side-effects/use-live-data',
        'state-and-side-effects/use-flow',
        'state-and-side-effects/use-callback',
      ],
      'Animations': [
        'transitions/state-based-animations',
        'transitions/animated-containers',
        'transitions/style-transitions',
        'transitions/dynamic-props-bindto',
        {
          'Legacy Transitions': [
            'transitions/migrating-from-transitions',
            'transitions/transition-basics',
            'transitions/transition-types',
            'transitions/transition-all-layout',
            'transitions/transition-choreography',
            'transitions/transition-key-types',
          ],
        },
      ],
    },
    'state-and-side-effects/keys-and-identity',
    'visibility/visibility-handling',
    'accessibility/accessibility',
    {
      'Testing': [
        'testing/testing-getting-started',
        'testing/testing-assertions',
        'testing/testing-actions',
      ],
      'Widgets': [
        'widgets/builtin-widgets',
        'widgets/canvas',
        ...fbInternalOnly(['widgets/fb/design-components']),
      ],
      'Adopting Litho': ['adopting-litho/migration-strategies'],
      'Legacy Litho API': [
        'legacy-spec-api/overview',
        'legacy-spec-api/custom-view-compat',
        'legacy-spec-api/layout-specs',
        'legacy-spec-api/mount-specs',
        'legacy-spec-api/dynamic-props',
        'legacy-spec-api/transition-definitions',
        'legacy-spec-api/keys-and-identity-legacy',
        'legacy-spec-api/accessibility-overview',
        {
          'Sections API': [
            'legacy-spec-api/sections/start',
            'legacy-spec-api/sections/recycler-collection-component',
            'legacy-spec-api/sections/best-practices',
            'legacy-spec-api/sections/hscrolls',
            'legacy-spec-api/sections/debugging-sections',
            {
              Advanced: [
                'legacy-spec-api/sections/advanced/working-ranges',
                'legacy-spec-api/sections/advanced/communicating-with-the-ui',
                'legacy-spec-api/sections/advanced/services',
                'legacy-spec-api/sections/advanced/view-support',
                'legacy-spec-api/sections/advanced/diff-sections',
                'legacy-spec-api/sections/advanced/architecture',
              ],
            },
          ],
        },
        {
          'Props, State, and Events for Specs': [
            'legacy-spec-api/passing-data-to-components/spec-props',
            'legacy-spec-api/passing-data-to-components/treeprops',
            'legacy-spec-api/passing-data-to-components/state-for-specs',
            'legacy-spec-api/passing-data-to-components/events-for-specs',
            'legacy-spec-api/passing-data-to-components/trigger-events',
          ],
        },
        'legacy-spec-api/tooling/android-studio-plugin-legacy',
        'legacy-spec-api/coding-style',
      ],
      'Migrating to the Kotlin API': [
        'migrating-to-kcomponents/kotlin-intro',
        'migrating-to-kcomponents/kotlin-api-basics',
        'migrating-to-kcomponents/hooks-for-spec-developers',
        'migrating-to-kcomponents/kotlin-transitions',
        'migrating-to-kcomponents/kotlin-flexbox-containers',
        'migrating-to-kcomponents/event-handling',
        'migrating-to-kcomponents/migrate-layout-spec',
        'migrating-to-kcomponents/migrating-from-mountspecs-to-primitives',
        'migrating-to-kcomponents/lazycollections-sections-migration',
        ...fbInternalOnly([
          'migrating-to-kcomponents/lazycollections-fb-internal',
        ]),
        'migrating-to-kcomponents/kotlin-api-cheatsheet',
      ],
      // TODO: clean this section up, add intro page
      'Tooling': [
        {
          'Android Studio': [
            'tooling/android-studio/android-studio-plugin',
            ...fbInternalOnly([
              'tooling/android-studio/fb/android-studio-ui-preview-intro',
            ]),
            ...fbInternalOnly([
              'tooling/android-studio/fb/android-studio-ui-preview',
            ]),
            ...fbInternalOnly([
              'tooling/android-studio/fb/android-studio-ui-preview-setup',
            ]),
          ],
          'Debugging': [
            'tooling/debugging/debugging-tips',
            'tooling/debugging/debugging-lists',
            ...fbInternalOnly(['tooling/debugging/fb/debugging-time-travel']),
          ],
          'Developer Tools': ['tooling/developer-tools/flipper-plugins'],
        },
      ],
      'Best Practices': [
        'best-practices/immutability',
        'best-practices/props-vs-state',
        'best-practices/hoisting-state',
        'best-practices/communicating-between-components',
      ],
    },
    ...fbInternalOnly(['archived-sections']),
  ],
};

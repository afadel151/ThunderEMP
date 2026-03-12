<script setup>
</script>

<template>
    <div class="flex min-h-full flex-col items-start bg-base-200 is-drawer-close:w-14 is-drawer-open:w-64">
                <!-- Sidebar content here -->
                <ul class="menu w-full grow">
                    <!-- List item -->
                    <li>
                        <button class="is-drawer-close:tooltip is-drawer-close:tooltip-right" data-tip="Homepage">
                            <!-- Home icon -->
                            <Icon name="material-symbols-light:move-to-inbox-rounded" class="my-1.5 inline-block size-4"/>

                            <span class="is-drawer-close:hidden">Inbox</span>
                        </button>
                    </li>

                    <!-- List item -->
                    <li>
                        <button class="is-drawer-close:tooltip is-drawer-close:tooltip-right" data-tip="Settings">
                            <!-- Settings icon -->
                             <!-- to edit for sidebar sizing , see sidebar in frontend -->
                            <Icon name="material-symbols-light:edit" class="my-1.5 inline-block size-4" />
                            <span class="is-drawer-close:hidden">Compose</span>
                        </button>
                    </li>
                </ul>
            </div>
</template>
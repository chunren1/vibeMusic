<script setup>
defineProps({
  song: { type: Object, required: true },
  playing: { type: Boolean, default: false },
  showFav: { type: Boolean, default: true },
  showIdx: { type: Boolean, default: false },
  idx: { type: Number, default: 0 },
})
defineEmits(['play', 'toggle-fav'])
</script>

<template>
  <div
    class="msli"
    :class="{ 'msli-playing': playing }"
    @click="$emit('play')"
  >
    <span v-if="showIdx" class="msli-idx">{{ idx + 1 }}</span>
    <div
      class="msli-cover"
      v-lazy-img:bg="song.coverUrl ? `${song.coverUrl}?param=80y80` : null"
      :style="!song.coverUrl ? { background: song.coverColor || 'var(--m-bg-card)' } : {}"
    >
      <span v-if="playing" class="msli-eq">
        <span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span>
      </span>
    </div>
    <div class="msli-info">
      <div class="msli-name">{{ song.name || song.songName }}</div>
      <div class="msli-artist">{{ song.artist }}</div>
    </div>
    <slot name="actions" />
    <button v-if="showFav" class="msli-fav" @click.stop="$emit('toggle-fav')">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2">
        <polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/>
      </svg>
    </button>
  </div>
</template>

<style scoped>
.msli {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 8px; border-radius: var(--m-radius-md);
  transition: background var(--m-duration-fast);
  content-visibility: auto;
  contain-intrinsic-size: auto 56px;
}
.msli:active { background: var(--m-bg-card-hover); }
.msli-playing { background: rgba(49,194,124,0.06); }
.msli-idx {
  width: 24px; text-align: center;
  font-size: var(--m-font-size-sm); color: var(--m-text-secondary);
  flex-shrink: 0;
}
.msli-cover {
  width: 44px; height: 44px; border-radius: var(--m-radius-sm); flex-shrink: 0;
  background: var(--m-bg-card) center/cover no-repeat;
  display: flex; align-items: center; justify-content: center;
}
.msli-eq { display: flex; align-items: flex-end; gap: 2px; height: 14px; }
.msli-eq .eq-bar { width: 2px; }
.msli-eq .eq-bar:nth-child(1) { height: 7px; }
.msli-eq .eq-bar:nth-child(2) { height: 12px; }
.msli-eq .eq-bar:nth-child(3) { height: 5px; }
.msli-info { flex: 1; min-width: 0; }
.msli-name {
  font-size: var(--m-font-size-md); color: var(--m-text-primary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.msli-artist {
  font-size: var(--m-font-size-sm); color: var(--m-text-secondary); margin-top: 2px;
}
.msli-fav {
  border: none; background: none; color: #555; padding: 4px; cursor: pointer; flex-shrink: 0;
}
.msli-fav:hover { color: #ffc107; }
</style>

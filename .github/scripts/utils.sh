sanitize_branch_name() {
  # - Replace '/' with '__' to preserve hierarchy
  # - Remove characters illegal in Docker tags
  # - Preserves case and allows a-z, A-Z, 0-9, ., _, -
  local branch_name="$1"
  echo "$branch_name" | sed -E 's#/#__#g; s#[^a-zA-Z0-9._-]#-#g'
}

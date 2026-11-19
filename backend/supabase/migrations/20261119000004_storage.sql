-- =====================================================================
-- Storage: private bucket for chapter images
-- ---------------------------------------------------------------------
-- Layout:  chapter-pages/{user_id}/{job_id}/original/{page_index}.{ext}
--          chapter-pages/{user_id}/{job_id}/rendered/{page_index}.webp
-- The app uploads originals only into its own {user_id}/ folder.
-- Rendered files are written by the backend (service_role).
-- Originals are never overwritten (no UPDATE policy).
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chapter-pages', 'chapter-pages', false, 15728640,
        array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy chapter_pages_read_own on storage.objects
  for select to authenticated
  using (bucket_id = 'chapter-pages' and (storage.foldername(name))[1] = auth.uid()::text);

create policy chapter_pages_upload_own_original on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'chapter-pages'
    and (storage.foldername(name))[1] = auth.uid()::text
    and (storage.foldername(name))[3] in ('original', 'cover')
  );

create policy chapter_pages_delete_own on storage.objects
  for delete to authenticated
  using (bucket_id = 'chapter-pages' and (storage.foldername(name))[1] = auth.uid()::text);

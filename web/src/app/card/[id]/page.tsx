import { Metadata } from "next";
import {
  StudentCardView,
  fetchStudentByCard,
  buildStudentCardMetadata,
} from "@/components/StudentCardView";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  return buildStudentCardMetadata(id);
}

export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const student = await fetchStudentByCard(id);
  return <StudentCardView id={id} student={student} />;
}

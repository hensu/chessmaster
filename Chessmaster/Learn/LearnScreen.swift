// Chessmaster — GPL-3.0-or-later
import SwiftUI

/// The Learn tab: themed puzzle categories on top (tied to the coaching
/// taxonomy), guided lessons below.
struct LearnScreen: View {
    @Environment(LearnProgress.self) private var progress

    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Puzzles")
                    .font(.title3.bold())
                    .padding(.horizontal)

                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(PuzzleCategory.allCases) { category in
                        NavigationLink(value: category) {
                            categoryCard(category)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)

                Text("Lessons")
                    .font(.title3.bold())
                    .padding(.horizontal)
                    .padding(.top, 6)

                VStack(spacing: 8) {
                    ForEach(LearnContent.lessons) { lesson in
                        NavigationLink(value: lesson) {
                            lessonRow(lesson)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Learn")
        .navigationDestination(for: PuzzleCategory.self) { PuzzleScreen(category: $0) }
        .navigationDestination(for: Lesson.self) { LessonScreen(lesson: $0) }
    }

    private func categoryCard(_ category: PuzzleCategory) -> some View {
        let total = LearnContent.puzzles(in: category).count
        let solved = progress.solvedCount(in: category)
        return VStack(alignment: .leading, spacing: 8) {
            Image(systemName: category.icon)
                .font(.title3)
                .foregroundStyle(solved == total && total > 0 ? .green : .accentColor)
            Text(category.title)
                .font(.subheadline.weight(.semibold))
            Text("\(solved)/\(total) solved")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 14))
    }

    private func lessonRow(_ lesson: Lesson) -> some View {
        HStack(spacing: 12) {
            Image(systemName: progress.completedLessons.contains(lesson.id)
                ? "checkmark.circle.fill" : "graduationcap")
                .font(.title3)
                .foregroundStyle(progress.completedLessons.contains(lesson.id) ? .green : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(lesson.title)
                    .font(.subheadline.weight(.semibold))
                Text(lesson.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 14))
    }
}

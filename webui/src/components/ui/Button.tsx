import { forwardRef, type ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type Variant = "primary" | "outline" | "ghost" | "danger";

const styles: Record<Variant, string> = {
  primary: "bg-paper text-void active:bg-ink disabled:bg-raised disabled:text-mute",
  outline: "border border-line text-ink active:bg-surface disabled:text-mute",
  ghost: "text-ink active:bg-surface disabled:text-mute",
  danger: "border border-danger/50 text-danger active:bg-danger/10",
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: "md" | "sm" | "icon";
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "outline", size = "md", className, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={cn(
        "inline-flex select-none items-center justify-center gap-2 font-medium transition-colors disabled:cursor-not-allowed",
        size === "md" && "h-11 rounded-full px-5 text-base",
        size === "sm" && "h-8 rounded-full px-3 text-sm",
        size === "icon" && "h-10 w-10 rounded-full",
        styles[variant],
        className,
      )}
      {...props}
    />
  );
});

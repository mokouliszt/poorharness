import * as RadixSwitch from "@radix-ui/react-switch";

export function Switch(props: { checked: boolean; onCheckedChange: (v: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <RadixSwitch.Root
      checked={props.checked}
      onCheckedChange={props.onCheckedChange}
      disabled={props.disabled}
      aria-label={props.label}
      className="relative h-7 w-12 shrink-0 rounded-full bg-raised transition-colors data-[state=checked]:bg-paper disabled:opacity-40"
    >
      <RadixSwitch.Thumb className="block h-5 w-5 translate-x-1 rounded-full bg-mute transition-transform data-[state=checked]:translate-x-6 data-[state=checked]:bg-void" />
    </RadixSwitch.Root>
  );
}
